import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import {
  MonitoringService,
  MonitoringSummary
} from '../../core/services/monitoring.service';

@Component({
  selector: 'app-admin-monitoring',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './admin-monitoring.component.html',
  styleUrl: './admin-monitoring.component.css'
})
export class AdminMonitoringComponent implements OnInit {
  summary: MonitoringSummary | null = null;
  loading = false;
  errorMessage = '';

  grafanaUrl = 'http://localhost:3000';
  prometheusUrl = 'http://localhost:9090/targets';

  constructor(private monitoringService: MonitoringService) {}

  ngOnInit(): void {
    this.loadMonitoringSummary();
  }

  loadMonitoringSummary(): void {
    this.loading = true;
    this.errorMessage = '';

    this.monitoringService.getSummary().subscribe({
      next: (data) => {
        this.summary = data;
        this.loading = false;
      },
      error: (error) => {
        console.error('Monitoring loading error:', error);
        this.errorMessage = 'Impossible de charger les métriques de monitoring.';
        this.loading = false;
      }
    });
  }

  openGrafana(): void {
    window.open(this.grafanaUrl, '_blank');
  }

  openPrometheus(): void {
    window.open(this.prometheusUrl, '_blank');
  }

  getHealthStatus(): string {
    if (!this.summary) {
      return 'UNKNOWN';
    }

    if (
      this.summary.failedDeployments > 0 ||
      this.summary.unavailableModelDeployments > 0 ||
      this.summary.failedRuns > 0
    ) {
      return 'WARNING';
    }

    return 'HEALTHY';
  }

  getHealthBadgeClass(): string {
    const status = this.getHealthStatus();

    if (status === 'HEALTHY') {
      return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400';
    }

    if (status === 'WARNING') {
      return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400';
    }

    return 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300';
  }

  getTotalRuns(): number {
    if (!this.summary) {
      return 0;
    }

    return (
      this.summary.completedRuns +
      this.summary.failedRuns +
      this.summary.runningRuns
    );
  }

  getRunCompletedPercent(): number {
    const total = this.getTotalRuns();

    if (!this.summary || total <= 0) {
      return 0;
    }

    return Math.round((this.summary.completedRuns / total) * 100);
  }

  getRunFailedPercent(): number {
    const total = this.getTotalRuns();

    if (!this.summary || total <= 0) {
      return 0;
    }

    return Math.round((this.summary.failedRuns / total) * 100);
  }

  getRunRunningPercent(): number {
    const total = this.getTotalRuns();

    if (!this.summary || total <= 0) {
      return 0;
    }

    return Math.round((this.summary.runningRuns / total) * 100);
  }

  getTotalDeploymentStatus(): number {
    if (!this.summary) {
      return 0;
    }

    return (
      this.summary.successfulDeployments +
      this.summary.failedDeployments +
      this.summary.deploymentsInProgress
    );
  }

  getDeploymentSuccessPercent(): number {
    const total = this.getTotalDeploymentStatus();

    if (!this.summary || total <= 0) {
      return 0;
    }

    return Math.round((this.summary.successfulDeployments / total) * 100);
  }

  getDeploymentFailedPercent(): number {
    const total = this.getTotalDeploymentStatus();

    if (!this.summary || total <= 0) {
      return 0;
    }

    return Math.round((this.summary.failedDeployments / total) * 100);
  }

  getDeploymentInProgressPercent(): number {
    const total = this.getTotalDeploymentStatus();

    if (!this.summary || total <= 0) {
      return 0;
    }

    return Math.round((this.summary.deploymentsInProgress / total) * 100);
  }

  getActiveAlertsCount(): number {
    if (!this.summary) {
      return 0;
    }

    let count = 0;

    if (this.summary.failedRuns > 0) {
      count++;
    }

    if (this.summary.failedDeployments > 0) {
      count++;
    }

    if (this.summary.unavailableModelDeployments > 0) {
      count++;
    }

    return count;
  }

  getHealthScore(): number {
    if (!this.summary) {
      return 0;
    }

    let score = 100;

    if (this.summary.failedRuns > 0) {
      score -= 25;
    }

    if (this.summary.failedDeployments > 0) {
      score -= 35;
    }

    if (this.summary.unavailableModelDeployments > 0) {
      score -= 30;
    }

    if (this.summary.runningRuns > 0) {
      score -= 10;
    }

    return Math.max(score, 0);
  }

  getRunsPieStyle(): string {
    const completed = this.getRunCompletedPercent();
    const failed = this.getRunFailedPercent();
    const running = this.getRunRunningPercent();

    return `conic-gradient(
      #22c55e 0% ${completed}%,
      #ef4444 ${completed}% ${completed + failed}%,
      #3b82f6 ${completed + failed}% ${completed + failed + running}%,
      #374151 ${completed + failed + running}% 100%
    )`;
  }

  getLatencyStatus(): string {
  if (!this.summary) {
    return 'UNKNOWN';
  }

  if (this.summary.averageBackendLatencyMs >= 1000) {
    return 'CRITICAL';
  }

  if (this.summary.averageBackendLatencyMs >= 500) {
    return 'WARNING';
  }

  return 'GOOD';
}

getLatencyBadgeClass(): string {
  const status = this.getLatencyStatus();

  if (status === 'GOOD') {
    return 'status-ok';
  }

  if (status === 'WARNING') {
    return 'status-warning';
  }

  if (status === 'CRITICAL') {
    return 'status-danger';
  }

  return 'status-info';
}

getLatencyGaugePercent(): number {
  if (!this.summary) {
    return 0;
  }

  const maxLatencyMs = 1000;
  const percent = (this.summary.averageBackendLatencyMs / maxLatencyMs) * 100;

  return Math.min(Math.round(percent), 100);
}

  getDeploymentsPieStyle(): string {
    const success = this.getDeploymentSuccessPercent();
    const failed = this.getDeploymentFailedPercent();
    const progress = this.getDeploymentInProgressPercent();

    return `conic-gradient(
      #22c55e 0% ${success}%,
      #ef4444 ${success}% ${success + failed}%,
      #eab308 ${success + failed}% ${success + failed + progress}%,
      #374151 ${success + failed + progress}% 100%
    )`;
  }

  formatDuration(seconds: number | null | undefined): string {
  if (!seconds || seconds <= 0) {
    return '0s';
  }

  const totalSeconds = Math.round(seconds);

  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const remainingSeconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}h ${minutes}min`;
  }

  if (minutes > 0) {
    return `${minutes}min ${remainingSeconds}s`;
  }

  return `${remainingSeconds}s`;
}

getRuntimeTotalRuns(): number {
  if (!this.summary) {
    return 0;
  }

  return this.summary.standardRunsCount + this.summary.dockerRunsCount;
}

getStandardRunsPercent(): number {
  const total = this.getRuntimeTotalRuns();

  if (!this.summary || total <= 0) {
    return 0;
  }

  return Math.round((this.summary.standardRunsCount / total) * 100);
}

getDockerRunsPercent(): number {
  const total = this.getRuntimeTotalRuns();

  if (!this.summary || total <= 0) {
    return 0;
  }

  return Math.round((this.summary.dockerRunsCount / total) * 100);
}

getRuntimePieStyle(): string {
  const standard = this.getStandardRunsPercent();
  const docker = this.getDockerRunsPercent();

  return `conic-gradient(
    #3b82f6 0% ${standard}%,
    #f97316 ${standard}% ${standard + docker}%,
    #374151 ${standard + docker}% 100%
  )`;
}
}