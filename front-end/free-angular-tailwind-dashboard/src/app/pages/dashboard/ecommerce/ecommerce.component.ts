import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { interval, Subscription } from 'rxjs';
import {
  DashboardBestModel,
  DashboardRecentRun,
  DashboardService,
  DashboardSummary
} from '../../../core/services/dashboard.service';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-ecommerce',
  imports: [CommonModule, RouterModule],
  templateUrl: './ecommerce.component.html',
})
export class EcommerceComponent implements OnInit, OnDestroy {
  summary: DashboardSummary | null = null;
  loading = true;
  error: string | null = null;
  autoRefresh = true;
  lastUpdated: Date | null = null;

  readonly firstName: string;

  private refreshSubscription: Subscription | null = null;

  constructor(
    private readonly dashboardService: DashboardService,
    authService: AuthService
  ) {
    const user = authService.getStoredUser();
    this.firstName = user?.firstName ?? 'bienvenue';
  }

  ngOnInit(): void {
    this.loadSummary();
    this.startAutoRefresh();
  }

  ngOnDestroy(): void {
    this.stopAutoRefresh();
  }

  loadSummary(): void {
    this.error = null;

    this.dashboardService.getSummary().subscribe({
      next: (summary) => {
        this.summary = summary;
        this.loading = false;
        this.lastUpdated = new Date();
      },
      error: () => {
        this.loading = false;
        this.error =
          'Impossible de charger les statistiques. Vérifiez que le backend est disponible.';
      }
    });
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;

    if (this.autoRefresh) {
      this.startAutoRefresh();
      this.loadSummary();
    } else {
      this.stopAutoRefresh();
    }
  }

  private startAutoRefresh(): void {
    if (this.refreshSubscription) {
      return;
    }

    this.refreshSubscription = interval(30000).subscribe(() => {
      this.loadSummary();
    });
  }

  private stopAutoRefresh(): void {
    if (this.refreshSubscription) {
      this.refreshSubscription.unsubscribe();
      this.refreshSubscription = null;
    }
  }

  formatMetricValue(value: number | null | undefined): string {
    if (value === null || value === undefined || Number.isNaN(value)) {
      return '—';
    }

    if (Math.abs(value) >= 1000) {
      return value.toFixed(2);
    }

    return value.toFixed(4);
  }

  formatDate(value: string | null | undefined): string {
    if (!value) {
      return '—';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  statusLabel(status: string | null): string {
    const map: Record<string, string> = {
      CREATED: 'Créé',
      RUNNING: 'En cours',
      COMPLETED: 'Réussi',
      FAILED: 'Échoué',
      CANCELLED: 'Annulé',
      DEPLOYED: 'Déployé',
      PENDING: 'En attente',
      DEPLOYING: 'Déploiement',
      OUT_OF_SYNC: 'Désynchronisé'
    };

    return map[status ?? ''] ?? status ?? '—';
  }

  statusTone(status: string | null): { dot: string; badge: string; text: string } {
    const upper = (status ?? '').toUpperCase();

    if (upper === 'RUNNING' || upper === 'DEPLOYING' || upper === 'PENDING') {
      return {
        dot: 'bg-blue-500',
        badge: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
        text: 'text-blue-600 dark:text-blue-400'
      };
    }

    if (upper === 'COMPLETED' || upper === 'DEPLOYED') {
      return {
        dot: 'bg-success-500',
        badge: 'bg-success-100 text-success-800 dark:bg-success-900/30 dark:text-success-400',
        text: 'text-success-600 dark:text-success-400'
      };
    }

    if (upper === 'FAILED' || upper === 'OUT_OF_SYNC') {
      return {
        dot: 'bg-error-500',
        badge: 'bg-error-100 text-error-800 dark:bg-error-900/30 dark:text-error-400',
        text: 'text-error-600 dark:text-error-400'
      };
    }

    if (upper === 'CANCELLED') {
      return {
        dot: 'bg-warning-500',
        badge: 'bg-warning-100 text-warning-800 dark:bg-warning-900/30 dark:text-warning-400',
        text: 'text-warning-600 dark:text-warning-400'
      };
    }

    return {
      dot: 'bg-gray-400',
      badge: 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-400',
      text: 'text-gray-600 dark:text-gray-400'
    };
  }

  recentRuns(): DashboardRecentRun[] {
    return this.summary?.recentRuns ?? [];
  }

  bestModels(): DashboardBestModel[] {
    return this.summary?.bestModels ?? [];
  }
}
