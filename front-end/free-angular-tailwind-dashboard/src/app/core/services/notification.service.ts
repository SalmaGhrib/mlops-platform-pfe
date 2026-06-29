import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { BehaviorSubject, Observable, forkJoin } from 'rxjs';
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
}

@Injectable({
  providedIn: 'root',
})
export class NotificationService {
  private readonly apiUrl = '/api/notifications';
  private readonly wsUrl = '/ws';

  private stompClient: Client | null = null;
  private connectedUserId: number | null = null;

  private notificationsSubject = new BehaviorSubject<NotificationResponse[]>([]);
  notifications$ = this.notificationsSubject.asObservable();

  private unreadCountSubject = new BehaviorSubject<number>(0);
  unreadCount$ = this.unreadCountSubject.asObservable();

  constructor(private http: HttpClient) {}

  loadNotifications(): void {
    forkJoin({
      notifications: this.getNotifications(),
      unreadCount: this.getUnreadCount(),
    }).subscribe({
      next: ({ notifications, unreadCount }) => {
        this.notificationsSubject.next(this.sortNotifications(notifications || []));
        this.unreadCountSubject.next(unreadCount || 0);
      },
      error: (error) => {
        console.error('[NOTIFICATIONS] Failed to load notifications', error);
      },
    });
  }

  getNotifications(): Observable<NotificationResponse[]> {
    return this.http.get<NotificationResponse[]>(this.apiUrl, {
      headers: this.getAuthHeaders(),
    });
  }

  getUnreadCount(): Observable<number> {
    return this.http.get<number>(`${this.apiUrl}/unread-count`, {
      headers: this.getAuthHeaders(),
    });
  }

  markAsRead(notificationId: number): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/${notificationId}/read`,
      {},
      { headers: this.getAuthHeaders() }
    );
  }

  markAllAsRead(): Observable<void> {
    return this.http.put<void>(
      `${this.apiUrl}/read-all`,
      {},
      { headers: this.getAuthHeaders() }
    );
  }

  refreshUnreadCount(): void {
    this.getUnreadCount().subscribe({
      next: (count) => {
        this.unreadCountSubject.next(count || 0);
      },
      error: (error) => {
        console.error('[NOTIFICATIONS] Failed to load unread count', error);
      },
    });
  }

  reloadAfterReadChange(): void {
    this.loadNotifications();
  }

  setNotifications(notifications: NotificationResponse[]): void {
    this.notificationsSubject.next(this.sortNotifications(notifications || []));
  }

  setUnreadCount(count: number): void {
    this.unreadCountSubject.next(count || 0);
  }

  connectWebSocket(): void {
    const token = this.getToken();
    const userId = this.getUserIdFromToken();

    if (!token || !userId) {
      console.warn('[NOTIFICATIONS] WebSocket skipped: missing token or userId');
      return;
    }

    if (this.stompClient?.connected && this.connectedUserId === userId) {
      return;
    }

    this.disconnectWebSocket();

    this.connectedUserId = userId;

    this.stompClient = new Client({
      webSocketFactory: () => new SockJS(this.wsUrl),
      reconnectDelay: 5000,
      debug: () => {},

      onConnect: () => {
        console.log('[NOTIFICATIONS] WebSocket connected for userId=', userId);

        this.stompClient?.subscribe(
          `/topic/users/${userId}/notifications`,
          (message: IMessage) => {
            this.handleIncomingNotification(message);
          }
        );

        this.loadNotifications();
      },

      onStompError: (frame) => {
        console.error('[NOTIFICATIONS] STOMP error', frame);
      },

      onWebSocketError: (error) => {
        console.error('[NOTIFICATIONS] WebSocket error', error);
      },
    });

    this.stompClient.activate();
  }

  disconnectWebSocket(): void {
    if (this.stompClient) {
      this.stompClient.deactivate();
      this.stompClient = null;
    }

    this.connectedUserId = null;
  }

  clearState(): void {
    this.notificationsSubject.next([]);
    this.unreadCountSubject.next(0);
    this.disconnectWebSocket();
  }

  private handleIncomingNotification(message: IMessage): void {
    try {
      const notification = JSON.parse(message.body) as NotificationResponse;

      const current = this.notificationsSubject.value || [];
      const exists = current.some((item) => item.id === notification.id);

      const updated = exists
        ? current.map((item) => item.id === notification.id ? notification : item)
        : [notification, ...current];

      this.notificationsSubject.next(this.sortNotifications(updated));

      /*
       * On recharge le vrai nombre depuis le backend.
       * Comme ça, pas de compteur faux ou aléatoire.
       */
      this.refreshUnreadCount();

    } catch (error) {
      console.error('[NOTIFICATIONS] Invalid WebSocket notification', error);
    }
  }

  private sortNotifications(notifications: NotificationResponse[]): NotificationResponse[] {
    return [...notifications].sort((a, b) => {
      const dateA = new Date(a.createdAt || 0).getTime();
      const dateB = new Date(b.createdAt || 0).getTime();
      return dateB - dateA;
    });
  }

  private getAuthHeaders(): HttpHeaders {
    const token = this.getToken();

    return new HttpHeaders({
      Authorization: token ? `Bearer ${token}` : '',
    });
  }

  private getToken(): string | null {
    return (
      localStorage.getItem('mlops_token') ||
      sessionStorage.getItem('mlops_token') ||
      localStorage.getItem('token') ||
      sessionStorage.getItem('token')
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

      const decodedPayload = JSON.parse(atob(payload));

      return (
        decodedPayload.userId ||
        decodedPayload.id ||
        decodedPayload.subId ||
        null
      );
    } catch (error) {
      console.error('[NOTIFICATIONS] Failed to decode token', error);
      return null;
    }
  }
}
