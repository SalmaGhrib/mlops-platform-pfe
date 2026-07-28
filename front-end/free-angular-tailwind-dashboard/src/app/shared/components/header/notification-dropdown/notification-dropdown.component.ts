import { CommonModule } from '@angular/common';
import {
  Component,
  OnDestroy,
  OnInit,
} from '@angular/core';
import { RouterModule } from '@angular/router';
import {
  finalize,
  Subscription,
} from 'rxjs';

import { DropdownComponent } from '../../ui/dropdown/dropdown.component';
import { DropdownItemComponent } from '../../ui/dropdown/dropdown-item/dropdown-item.component';

import {
  NotificationResponse,
  NotificationService,
} from '../../../../core/services/notification.service';

@Component({
  selector: 'app-notification-dropdown',
  standalone: true,
  templateUrl: './notification-dropdown.component.html',
  imports: [
    CommonModule,
    RouterModule,
    DropdownComponent,
    DropdownItemComponent,
  ],
})
export class NotificationDropdownComponent
  implements OnInit, OnDestroy {

  isOpen = false;

  notifications: NotificationResponse[] = [];
  unreadCount = 0;

  loading = false;
  markingAllAsRead = false;

  private readonly subscriptions = new Subscription();

  constructor(
    private readonly notificationService: NotificationService
  ) {}

  ngOnInit(): void {
    this.loading = true;

    this.subscriptions.add(
      this.notificationService.notifications$.subscribe({
        next: (
          notifications: NotificationResponse[]
        ): void => {
          this.notifications = notifications ?? [];
          this.loading = false;
        },

        error: (error: unknown): void => {
          console.error(
            '[NOTIFICATIONS] Notifications stream error',
            error
          );

          this.loading = false;
        },
      })
    );

    this.subscriptions.add(
      this.notificationService.unreadCount$.subscribe({
        next: (count: number): void => {
          this.unreadCount = Math.max(
            0,
            Number(count ?? 0)
          );
        },

        error: (error: unknown): void => {
          console.error(
            '[NOTIFICATIONS] Unread count stream error',
            error
          );
        },
      })
    );

    this.notificationService.loadNotifications();
    this.notificationService.connectWebSocket();
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  toggleDropdown(): void {
    this.isOpen = !this.isOpen;

    if (this.isOpen) {
      /*
       * On recharge seulement les notifications.
       * L'ouverture du menu ne les marque pas comme lues.
       */
      this.notificationService.loadNotifications();
    }
  }

  closeDropdown(): void {
    this.isOpen = false;
  }

  onCloseButtonClick(event: MouseEvent): void {
    event.stopPropagation();
    this.closeDropdown();
  }

  onMarkAllAsReadClick(event: MouseEvent): void {
    event.stopPropagation();
    this.markAllAsRead();
  }

  markAllAsRead(): void {
    if (
      this.markingAllAsRead ||
      this.unreadCount <= 0
    ) {
      return;
    }

    this.markingAllAsRead = true;

    this.notificationService
      .markAllAsRead()
      .pipe(
        finalize((): void => {
          this.markingAllAsRead = false;
        })
      )
      .subscribe({
        next: (): void => {
          /*
           * Le service met déjà à jour :
           * - la liste locale ;
           * - l'état read ;
           * - le compteur non lu.
           */
          console.log(
            '[NOTIFICATIONS] All notifications marked as read'
          );
        },

        error: (error: unknown): void => {
          console.error(
            '[NOTIFICATIONS] Failed to mark all as read',
            error
          );

          /*
           * Recharge l'état réel si le backend échoue.
           */
          this.notificationService.loadNotifications();
        },
      });
  }

  get unreadNotifications(): NotificationResponse[] {
    return this.notifications.filter(
      (notification: NotificationResponse) =>
        !notification.read
    );
  }

  get readNotifications(): NotificationResponse[] {
    return this.notifications.filter(
      (notification: NotificationResponse) =>
        notification.read
    );
  }

  getIconLabel(type: string): string {
    switch (type) {
      case 'RUN_COMPLETED':
        return 'RC';

      case 'RUN_FAILED':
        return 'RF';

      case 'DEPLOYMENT_DEPLOYED':
        return 'DD';

      case 'DEPLOYMENT_FAILED':
        return 'DF';

      case 'DEPLOYMENT_OUT_OF_SYNC':
        return 'OS';

      case 'MONITORING_ALERT':
        return 'AL';

      default:
        return 'IN';
    }
  }

  getIconClass(type: string): string {
    switch (type) {
      case 'RUN_COMPLETED':
      case 'DEPLOYMENT_DEPLOYED':
        return (
          'bg-green-100 text-green-700 ' +
          'dark:bg-green-900/30 dark:text-green-400'
        );

      case 'RUN_FAILED':
      case 'DEPLOYMENT_FAILED':
        return (
          'bg-red-100 text-red-700 ' +
          'dark:bg-red-900/30 dark:text-red-400'
        );

      case 'DEPLOYMENT_OUT_OF_SYNC':
      case 'MONITORING_ALERT':
        return (
          'bg-yellow-100 text-yellow-700 ' +
          'dark:bg-yellow-900/30 dark:text-yellow-400'
        );

      default:
        return (
          'bg-blue-100 text-blue-700 ' +
          'dark:bg-blue-900/30 dark:text-blue-400'
        );
    }
  }

  getTypeLabel(type: string): string {
    switch (type) {
      case 'RUN_COMPLETED':
        return 'Run terminé';

      case 'RUN_FAILED':
        return 'Run échoué';

      case 'DEPLOYMENT_DEPLOYED':
        return 'Déploiement réussi';

      case 'DEPLOYMENT_FAILED':
        return 'Déploiement échoué';

      case 'DEPLOYMENT_OUT_OF_SYNC':
        return 'Déploiement désynchronisé';

      case 'MONITORING_ALERT':
        return 'Alerte monitoring';

      default:
        return 'Notification';
    }
  }

  getTargetLabel(targetType: string): string {
    switch (targetType) {
      case 'EXPERIMENT':
        return 'Run';

      case 'DEPLOYMENT':
        return 'Déploiement';

      case 'MONITORING':
        return 'Monitoring';

      case 'PROJECT':
        return 'Projet';

      default:
        return 'Système';
    }
  }

  formatDate(value: string): string {
    const date = this.parseBackendDate(value);

    if (!date) {
      return '';
    }

    const now = new Date();

    let differenceMs =
      now.getTime() - date.getTime();

    if (differenceMs < 0) {
      differenceMs = 0;
    }

    const differenceMinutes =
      Math.floor(differenceMs / 60_000);

    const differenceHours =
      Math.floor(differenceMinutes / 60);

    const differenceDays =
      Math.floor(differenceHours / 24);

    if (differenceMinutes < 1) {
      return 'À l’instant';
    }

    if (differenceMinutes < 60) {
      return `${differenceMinutes} min`;
    }

    if (differenceHours < 24) {
      return `${differenceHours} h`;
    }

    return `${differenceDays} j`;
  }

  private parseBackendDate(
    value: string | null | undefined
  ): Date | null {
    if (!value) {
      return null;
    }

    const cleanValue = String(value).trim();

    if (!cleanValue) {
      return null;
    }

    const hasTimezone =
      /([zZ]|[+-]\d{2}:?\d{2})$/.test(cleanValue);

    const normalizedValue = hasTimezone
      ? cleanValue
      : `${cleanValue}Z`;

    const date = new Date(normalizedValue);

    if (Number.isNaN(date.getTime())) {
      return null;
    }

    return date;
  }
}