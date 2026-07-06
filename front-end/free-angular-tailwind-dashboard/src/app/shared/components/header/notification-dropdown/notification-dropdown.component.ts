import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { RouterModule } from '@angular/router';
import { Subscription } from 'rxjs';

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
export class NotificationDropdownComponent implements OnInit, OnDestroy {
  isOpen = false;

  notifications: NotificationResponse[] = [];
  unreadCount = 0;
  loading = false;
  autoMarkingAsRead = false;

  private subscriptions = new Subscription();

  constructor(private notificationService: NotificationService) {}

  ngOnInit(): void {
    this.loading = true;

    this.subscriptions.add(
      this.notificationService.notifications$.subscribe(
        (notifications: NotificationResponse[]) => {
          this.notifications = notifications || [];
          this.loading = false;
        }
      )
    );

    this.subscriptions.add(
      this.notificationService.unreadCount$.subscribe((count: number) => {
        this.unreadCount = count || 0;
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
      this.loadAndAutoMarkAsRead();
    }
  }

  closeDropdown(): void {
    this.isOpen = false;
  }

  private loadAndAutoMarkAsRead(): void {
    this.loading = true;

    this.notificationService.getNotifications().subscribe({
      next: (notifications: NotificationResponse[]) => {
        const loadedNotifications = notifications || [];
        this.notifications = loadedNotifications;
        this.notificationService.setNotifications(loadedNotifications);
        this.loading = false;

        const hasUnread = loadedNotifications.some(
          (notification: NotificationResponse) => !notification.read
        );

        if (!hasUnread) {
          this.notificationService.refreshUnreadCount();
          return;
        }

        this.autoMarkingAsRead = true;

        const readNotifications = loadedNotifications.map(
          (notification: NotificationResponse) => ({
            ...notification,
            read: true,
          })
        );

        /*
         * Mise à jour immédiate côté interface :
         * dès que l’utilisateur ouvre l’onglet, les notifications deviennent lues visuellement.
         */
        this.notifications = readNotifications;
        this.notificationService.setNotifications(readNotifications);
        this.notificationService.setUnreadCount(0);

        /*
         * Mise à jour réelle côté backend.
         */
        this.notificationService.markAllAsRead().subscribe({
          next: (): void => {
            this.autoMarkingAsRead = false;
            this.notificationService.reloadAfterReadChange();
          },
          error: (error: unknown): void => {
            console.error('[NOTIFICATIONS] Failed to auto mark all as read', error);
            this.autoMarkingAsRead = false;
            this.notificationService.reloadAfterReadChange();
          },
        });
      },
      error: (error: unknown): void => {
        console.error('[NOTIFICATIONS] Failed to load notifications', error);
        this.loading = false;
      },
    });
  }

  get unreadNotifications(): NotificationResponse[] {
    return this.notifications.filter((item) => !item.read);
  }

  get readNotifications(): NotificationResponse[] {
    return this.notifications.filter((item) => item.read);
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
        return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400';

      case 'RUN_FAILED':
      case 'DEPLOYMENT_FAILED':
        return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400';

      case 'DEPLOYMENT_OUT_OF_SYNC':
      case 'MONITORING_ALERT':
        return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400';

      default:
        return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400';
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
    if (!value) {
      return '';
    }

    const date = this.parseBackendDate(value);

    if (!date) {
      return '';
    }

    const now = new Date();
    let diffMs = now.getTime() - date.getTime();

    /*
     * Sécurité : si l’heure backend est légèrement dans le futur
     * à cause d’un décalage serveur/client, on affiche À l’instant.
     */
    if (diffMs < 0) {
      diffMs = 0;
    }

    const diffMinutes = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMinutes / 60);
    const diffDays = Math.floor(diffHours / 24);

    if (diffMinutes < 1) {
      return 'À l’instant';
    }

    if (diffMinutes < 60) {
      return `${diffMinutes} min`;
    }

    if (diffHours < 24) {
      return `${diffHours} h`;
    }

    return `${diffDays} j`;
  }

  private parseBackendDate(value: string | null | undefined): Date | null {
    if (!value) {
      return null;
    }

    const clean = String(value).trim();

    if (!clean) {
      return null;
    }

    /*
     * Si le backend renvoie déjà Z ou +00:00, on garde tel quel.
     * Si le backend renvoie un LocalDateTime sans timezone, on le considère UTC.
     */
    const hasTimezone = /([zZ]|[+-]\d{2}:?\d{2})$/.test(clean);
    const normalized = hasTimezone ? clean : `${clean}Z`;

    const date = new Date(normalized);

    if (Number.isNaN(date.getTime())) {
      return null;
    }

    return date;
  }
}