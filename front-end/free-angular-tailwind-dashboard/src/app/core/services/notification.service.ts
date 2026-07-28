import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';

import {
  BehaviorSubject,
  Observable,
  forkJoin,
  finalize,
  shareReplay,
  tap,
} from 'rxjs';

import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

export type NotificationType =
  | 'RUN_COMPLETED'
  | 'RUN_FAILED'
  | 'DEPLOYMENT_DEPLOYED'
  | 'DEPLOYMENT_FAILED'
  | 'DEPLOYMENT_OUT_OF_SYNC'
  | 'MONITORING_ALERT'
  | 'SYSTEM_INFO';

export type NotificationTargetType =
  | 'EXPERIMENT'
  | 'DEPLOYMENT'
  | 'PROJECT'
  | 'MONITORING'
  | 'SYSTEM';

export interface NotificationResponse {
  id: number;
  title: string;
  message: string;
  type: NotificationType;
  targetType: NotificationTargetType;
  targetId: number | null;
  read: boolean;
  adminNotification: boolean;
  createdAt: string;
  recipientUserId: number | null;
}

@Injectable({
  providedIn: 'root',
})
export class NotificationService {
  private readonly apiUrl = '/api/notifications';
  private readonly wsUrl = '/ws';

  private stompClient: Client | null = null;
  private connectedUserId: number | null = null;

  private loadingNotifications = false;

  private markAllRequest$: Observable<void> | null = null;

  private readonly notificationsSubject =
    new BehaviorSubject<NotificationResponse[]>([]);

  readonly notifications$ =
    this.notificationsSubject.asObservable();

  private readonly unreadCountSubject =
    new BehaviorSubject<number>(0);

  readonly unreadCount$ =
    this.unreadCountSubject.asObservable();

  constructor(private readonly http: HttpClient) {}

  loadNotifications(): void {
    if (this.loadingNotifications) {
      return;
    }

    this.loadingNotifications = true;

    forkJoin({
      notifications: this.getNotifications(),
      unreadCount: this.getUnreadCount(),
    })
      .pipe(
        finalize(() => {
          this.loadingNotifications = false;
        })
      )
      .subscribe({
        next: ({ notifications, unreadCount }) => {
          this.notificationsSubject.next(
            this.sortNotifications(notifications ?? [])
          );

          this.unreadCountSubject.next(
            Number(unreadCount ?? 0)
          );
        },

        error: (error) => {
          console.error(
            '[NOTIFICATIONS] Failed to load notifications',
            error
          );
        },
      });
  }

  getNotifications(): Observable<NotificationResponse[]> {
    return this.http.get<NotificationResponse[]>(
      this.apiUrl,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  getUnreadCount(): Observable<number> {
    return this.http.get<number>(
      `${this.apiUrl}/unread-count`,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  markAsRead(
    notificationId: number
  ): Observable<void> {
    return this.http
      .put<void>(
        `${this.apiUrl}/${notificationId}/read`,
        {},
        {
          headers: this.getAuthHeaders(),
        }
      )
      .pipe(
        tap(() => {
          const current =
            this.notificationsSubject.value;

          const selected =
            current.find(
              (notification) =>
                notification.id === notificationId
            );

          const updated =
            current.map((notification) =>
              notification.id === notificationId
                ? {
                    ...notification,
                    read: true,
                  }
                : notification
            );

          this.notificationsSubject.next(updated);

          if (selected && !selected.read) {
            this.unreadCountSubject.next(
              Math.max(
                0,
                this.unreadCountSubject.value - 1
              )
            );
          }
        })
      );
  }

  markAllAsRead(): Observable<void> {
    /*
     * Si un appel est déjà en cours, on réutilise
     * la même requête au lieu d'en envoyer une deuxième.
     */
    if (this.markAllRequest$) {
      return this.markAllRequest$;
    }

    this.markAllRequest$ = this.http
      .put<void>(
        `${this.apiUrl}/read-all`,
        {},
        {
          headers: this.getAuthHeaders(),
        }
      )
      .pipe(
        tap(() => {
          const updated =
            this.notificationsSubject.value.map(
              (notification) => ({
                ...notification,
                read: true,
              })
            );

          this.notificationsSubject.next(updated);
          this.unreadCountSubject.next(0);
        }),

        finalize(() => {
          this.markAllRequest$ = null;
        }),

        shareReplay({
          bufferSize: 1,
          refCount: false,
        })
      );

    return this.markAllRequest$;
  }

  refreshUnreadCount(): void {
    this.getUnreadCount().subscribe({
      next: (count) => {
        this.unreadCountSubject.next(
          Number(count ?? 0)
        );
      },

      error: (error) => {
        console.error(
          '[NOTIFICATIONS] Failed to load unread count',
          error
        );
      },
    });
  }

  reloadAfterReadChange(): void {
    this.loadNotifications();
  }

  setNotifications(
    notifications: NotificationResponse[]
  ): void {
    this.notificationsSubject.next(
      this.sortNotifications(
        notifications ?? []
      )
    );
  }

  setUnreadCount(count: number): void {
    this.unreadCountSubject.next(
      Math.max(0, Number(count ?? 0))
    );
  }

  connectWebSocket(): void {
    const token = this.getToken();
    const userId = this.getUserIdFromToken();

    if (!token || !userId) {
      console.warn(
        '[NOTIFICATIONS] WebSocket skipped: missing token or userId'
      );

      return;
    }

    if (
      this.stompClient?.connected &&
      this.connectedUserId === userId
    ) {
      return;
    }

    if (
      this.stompClient?.active &&
      this.connectedUserId === userId
    ) {
      return;
    }

    this.disconnectWebSocket();

    this.connectedUserId = userId;

    this.stompClient = new Client({
      webSocketFactory: () =>
        new SockJS(this.wsUrl),

      reconnectDelay: 5000,

      debug: () => {},

      onConnect: () => {
        console.log(
          '[NOTIFICATIONS] WebSocket connected for userId=',
          userId
        );

        this.stompClient?.subscribe(
          `/topic/users/${userId}/notifications`,
          (message: IMessage) => {
            this.handleIncomingNotification(message);
          }
        );

        this.loadNotifications();
      },

      onStompError: (frame) => {
        console.error(
          '[NOTIFICATIONS] STOMP error',
          frame
        );
      },

      onWebSocketError: (error) => {
        console.error(
          '[NOTIFICATIONS] WebSocket error',
          error
        );
      },
    });

    this.stompClient.activate();
  }

  disconnectWebSocket(): void {
    const client = this.stompClient;

    this.stompClient = null;
    this.connectedUserId = null;

    if (!client) {
      return;
    }

    void client.deactivate();
  }

  clearState(): void {
    this.notificationsSubject.next([]);
    this.unreadCountSubject.next(0);

    this.loadingNotifications = false;
    this.markAllRequest$ = null;

    this.disconnectWebSocket();
  }

  private handleIncomingNotification(
    message: IMessage
  ): void {
    try {
      const notification =
        JSON.parse(message.body) as NotificationResponse;

      const currentUserId =
        this.getUserIdFromToken();

      if (
        !currentUserId ||
        !notification.recipientUserId ||
        Number(notification.recipientUserId) !==
          Number(currentUserId)
      ) {
        console.warn(
          '[NOTIFICATIONS] Ignored notification for another user',
          {
            recipientUserId:
              notification.recipientUserId,

            currentUserId,
          }
        );

        return;
      }

      const current =
        this.notificationsSubject.value ?? [];

      const existing =
        current.find(
          (item) => item.id === notification.id
        );

      const updated = existing
        ? current.map((item) =>
            item.id === notification.id
              ? notification
              : item
          )
        : [notification, ...current];

      this.notificationsSubject.next(
        this.sortNotifications(updated)
      );

      if (!existing && !notification.read) {
        this.unreadCountSubject.next(
          this.unreadCountSubject.value + 1
        );
      }
    } catch (error) {
      console.error(
        '[NOTIFICATIONS] Invalid WebSocket notification',
        error
      );
    }
  }

  private sortNotifications(
    notifications: NotificationResponse[]
  ): NotificationResponse[] {
    return [...notifications].sort((a, b) => {
      const dateA =
        this.toTimestamp(a.createdAt);

      const dateB =
        this.toTimestamp(b.createdAt);

      return dateB - dateA;
    });
  }

  private toTimestamp(
    value: string | null | undefined
  ): number {
    const date =
      this.parseBackendDate(value);

    return date ? date.getTime() : 0;
  }

  private parseBackendDate(
    value: string | null | undefined
  ): Date | null {
    if (!value) {
      return null;
    }

    const clean =
      String(value).trim();

    if (!clean) {
      return null;
    }

    const hasTimezone =
      /([zZ]|[+-]\d{2}:?\d{2})$/.test(clean);

    const normalized =
      hasTimezone
        ? clean
        : `${clean}Z`;

    const date =
      new Date(normalized);

    return Number.isNaN(date.getTime())
      ? null
      : date;
  }

  private getAuthHeaders(): HttpHeaders {
    const token = this.getToken();

    if (!token) {
      return new HttpHeaders();
    }

    return new HttpHeaders({
      Authorization: `Bearer ${token}`,
    });
  }

  private getToken(): string | null {
    return (
      sessionStorage.getItem('mlops_token') ||
      sessionStorage.getItem('token') ||
      localStorage.getItem('mlops_token') ||
      localStorage.getItem('token')
    );
  }

  private getUserIdFromToken(): number | null {
    const token = this.getToken();

    if (!token) {
      return null;
    }

    try {
      const payload = token.split('.')[1];

      if (!payload) {
        return null;
      }

      const normalizedPayload = payload
        .replace(/-/g, '+')
        .replace(/_/g, '/')
        .padEnd(
          Math.ceil(payload.length / 4) * 4,
          '='
        );

      const decodedPayload =
        JSON.parse(atob(normalizedPayload));

      const rawUserId =
        decodedPayload.userId ??
        decodedPayload.id ??
        decodedPayload.subId ??
        null;

      if (rawUserId === null) {
        return null;
      }

      const userId = Number(rawUserId);

      return Number.isFinite(userId)
        ? userId
        : null;
    } catch (error) {
      console.error(
        '[NOTIFICATIONS] Failed to decode token',
        error
      );

      return null;
    }
  }
}