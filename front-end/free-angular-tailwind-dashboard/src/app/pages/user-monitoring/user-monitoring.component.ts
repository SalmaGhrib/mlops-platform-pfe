import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApexChart,
  ApexDataLabels,
  ApexLegend,
  ApexNonAxisChartSeries,
  ApexPlotOptions,
  ApexResponsive,
  ApexStroke,
  ApexTitleSubtitle,
  NgApexchartsModule
} from 'ng-apexcharts';

import {
  UserMonitoringDashboardResponse,
  UserMonitoringProjectDashboard,
  UserMonitoringProjectOption,
  UserMonitoringService,
  UserMonitoringSummary
} from '../../core/services/user-monitoring.service';

import {
  MlProjectResponse,
  ProjectService
} from '../../core/services/project.service';

export type DonutChartOptions = {
  series: ApexNonAxisChartSeries;
  chart: ApexChart;
  labels: string[];
  legend: ApexLegend;
  dataLabels: ApexDataLabels;
  plotOptions: ApexPlotOptions;
  stroke: ApexStroke;
  responsive: ApexResponsive[];
  title?: ApexTitleSubtitle;
};

@Component({
  selector: 'app-user-monitoring',
  standalone: true,
  imports: [CommonModule, FormsModule, NgApexchartsModule],
  templateUrl: './user-monitoring.component.html',
  styleUrls: ['./user-monitoring.component.css']
})
export class UserMonitoringComponent implements OnInit {
  loading = false;
  errorMessage = '';

  projects: UserMonitoringProjectOption[] = [];
  selectedProjectId: number | null = null;

  summary: UserMonitoringSummary | null = null;
  projectDashboard: UserMonitoringProjectDashboard | null = null;

  standardModelCount = 0;
  dockerizedModelCount = 0;

  healthChart!: Partial<DonutChartOptions>;
  runSuccessChart!: Partial<DonutChartOptions>;
  deploymentSuccessChart!: Partial<DonutChartOptions>;
  predictionSuccessChart!: Partial<DonutChartOptions>;
  runsDistributionChart!: Partial<DonutChartOptions>;
  deploymentsDistributionChart!: Partial<DonutChartOptions>;

  constructor(
    private readonly userMonitoringService: UserMonitoringService,
    private readonly projectService: ProjectService
  ) {}

  ngOnInit(): void {
    this.initializeCharts();
    this.loadDashboard();
  }

  loadDashboard(): void {
    this.loading = true;
    this.errorMessage = '';

    this.userMonitoringService.getDashboard(this.selectedProjectId).subscribe({
      next: (response: UserMonitoringDashboardResponse) => {
        this.projects = response.projects ?? [];
        this.summary = response.summary ?? null;
        this.projectDashboard = response.projectDashboard ?? null;

        if (
          this.selectedProjectId === null &&
          this.projects.length > 0
        ) {
          this.selectedProjectId = this.projects[0].id;
        }

        this.updateCharts();
        this.loadModelDistribution();
        this.loading = false;
      },
      error: (error) => {
        console.error('User monitoring loading error:', error);
        this.errorMessage = 'Impossible de charger le monitoring utilisateur.';
        this.loading = false;
      }
    });
  }

  onProjectChange(): void {
    this.loadDashboard();
  }

  refresh(): void {
    this.loadDashboard();
  }

  get runFailureRate(): number {
    const completed = this.summary?.completedRuns ?? 0;
    const failed = this.summary?.failedRuns ?? 0;

    if (completed + failed === 0) {
      return 0;
    }

    return this.normalizePercent((failed / (completed + failed)) * 100);
  }

  get deploymentFailureRate(): number {
    const total = this.summary?.totalDeployments ?? 0;
    const failed = this.summary?.failedDeployments ?? 0;

    if (total === 0) {
      return 0;
    }

    return this.normalizePercent((failed / total) * 100);
  }

  private loadModelDistribution(): void {
    this.projectService.getMyProjects().subscribe({
      next: (projects: MlProjectResponse[]) => {
        const dockerized = (projects || []).filter((project) => {
          const mode = String(project.runtimeMode || 'STANDARD_RUNTIME').toUpperCase();
          return mode === 'CUSTOM_DOCKERFILE';
        }).length;

        this.dockerizedModelCount = dockerized;
        this.standardModelCount = Math.max(0, (projects || []).length - dockerized);
      },
      error: () => {
        this.standardModelCount = 0;
        this.dockerizedModelCount = 0;
      }
    });
  }

  trackByProject(_: number, item: UserMonitoringProjectOption): number {
    return item.id;
  }

  private initializeCharts(): void {
    this.healthChart = this.buildPercentDonut('État global', 0);
    this.runSuccessChart = this.buildPercentDonut('Réussite des runs', 0);
    this.deploymentSuccessChart = this.buildPercentDonut('Réussite déploiements', 0);
    this.predictionSuccessChart = this.buildPercentDonut('Succès prédictions', 0);

    this.runsDistributionChart = this.buildDistributionDonut(
      'Répartition des runs',
      ['Completed', 'Failed', 'Running'],
      [0, 0, 0]
    );

    this.deploymentsDistributionChart = this.buildDistributionDonut(
      'Répartition des déploiements',
      ['Successful', 'Failed', 'In Progress'],
      [0, 0, 0]
    );
  }

  private updateCharts(): void {
    const dashboard = this.projectDashboard;
    const summary = this.summary;

    const healthScore = dashboard?.healthScore ?? 0;
    const runSuccess = dashboard?.runSuccessRate ?? summary?.runSuccessRate ?? 0;
    const deploymentSuccess = dashboard?.deploymentSuccessRate ?? summary?.deploymentSuccessRate ?? 0;
    const predictionSuccess = dashboard?.predictionSuccessRate ?? 0;

    this.healthChart = this.buildPercentDonut('État global', healthScore);
    this.runSuccessChart = this.buildPercentDonut('Réussite des runs', runSuccess);
    this.deploymentSuccessChart = this.buildPercentDonut('Réussite déploiements', deploymentSuccess);
    this.predictionSuccessChart = this.buildPercentDonut('Succès prédictions', predictionSuccess);

    this.runsDistributionChart = this.buildDistributionDonut(
      'Répartition des runs',
      ['Completed', 'Failed', 'Running'],
      [
        dashboard?.completedRuns ?? summary?.completedRuns ?? 0,
        dashboard?.failedRuns ?? summary?.failedRuns ?? 0,
        dashboard?.runningRuns ?? summary?.runningRuns ?? 0
      ]
    );

    this.deploymentsDistributionChart = this.buildDistributionDonut(
      'Répartition des déploiements',
      ['Successful', 'Failed', 'In Progress'],
      [
        summary?.successfulDeployments ?? 0,
        summary?.failedDeployments ?? 0,
        summary?.deploymentsInProgress ?? 0
      ]
    );
  }

  private buildPercentDonut(title: string, value: number): Partial<DonutChartOptions> {
    const safeValue = this.normalizePercent(value);

    return {
      series: [safeValue, 100 - safeValue],
      chart: {
        type: 'donut',
        height: 280
      },
      labels: ['Succès', 'Reste'],
      legend: {
        show: false
      },
      dataLabels: {
        enabled: true,
        formatter: (val: number, opts?: any) => {
          if (opts?.seriesIndex === 0) {
            return `${safeValue.toFixed(1)}%`;
          }
          return '';
        }
      },
      plotOptions: {
        pie: {
          donut: {
            size: '72%',
            labels: {
              show: true,
              name: {
                show: true
              },
              value: {
                show: true,
                formatter: () => `${safeValue.toFixed(1)}%`
              },
              total: {
                show: true,
                label: title,
                formatter: () => `${safeValue.toFixed(1)}%`
              }
            }
          }
        }
      },
      stroke: {
        width: 0
      },
      responsive: [
        {
          breakpoint: 768,
          options: {
            chart: {
              height: 240
            }
          }
        }
      ],
      title: {
        text: title,
        align: 'left'
      }
    };
  }

  private buildDistributionDonut(
    title: string,
    labels: string[],
    values: number[]
  ): Partial<DonutChartOptions> {
    return {
      series: values,
      chart: {
        type: 'donut',
        height: 300
      },
      labels,
      legend: {
        position: 'bottom'
      },
      dataLabels: {
        enabled: true,
        formatter: (val: number) => `${val.toFixed(1)}%`
      },
      plotOptions: {
        pie: {
          donut: {
            size: '68%',
            labels: {
              show: true,
              total: {
                show: true,
                label: title,
                formatter: (w) => {
                  const total = w.globals.seriesTotals.reduce(
                    (acc: number, curr: number) => acc + curr,
                    0
                  );
                  return `${total}`;
                }
              }
            }
          }
        }
      },
      stroke: {
        width: 0
      },
      responsive: [
        {
          breakpoint: 768,
          options: {
            chart: {
              height: 260
            }
          }
        }
      ],
      title: {
        text: title,
        align: 'left'
      }
    };
  }

  asPercent(value: number | null | undefined): string {
    return `${this.normalizePercent(value).toFixed(1)}%`;
  }

  asNumber(value: number | null | undefined): string {
    if (value === null || value === undefined) {
      return '0';
    }
    return `${value}`;
  }

  formatDate(value: string | null | undefined): string {
    if (!value) {
      return '-';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString('fr-FR');
  }

  badgeClass(status: string | null | undefined): string {
    const value = (status ?? '').toLowerCase();

    if (
      value.includes('success') ||
      value.includes('completed') ||
      value.includes('normal') ||
      value.includes('deployed') ||
      value.includes('active')
    ) {
      return 'badge success';
    }

    if (
      value.includes('fail') ||
      value.includes('error') ||
      value.includes('firing')
    ) {
      return 'badge danger';
    }

    if (
      value.includes('running') ||
      value.includes('progress') ||
      value.includes('warning')
    ) {
      return 'badge warning';
    }

    return 'badge neutral';
  }

  severityClass(severity: string | null | undefined): string {
    const value = (severity ?? '').toLowerCase();

    if (value.includes('critical') || value.includes('high')) {
      return 'row-icon-danger';
    }

    if (value.includes('warning') || value.includes('medium')) {
      return 'row-icon-warning';
    }

    return 'row-icon-info';
  }

  activityStatusClass(status: string | null | undefined): string {
    const value = (status ?? '').toLowerCase();

    if (
      value.includes('success') ||
      value.includes('completed') ||
      value.includes('deployed') ||
      value.includes('active')
    ) {
      return 'row-icon-success';
    }

    if (
      value.includes('fail') ||
      value.includes('error') ||
      value.includes('firing')
    ) {
      return 'row-icon-danger';
    }

    if (
      value.includes('running') ||
      value.includes('progress') ||
      value.includes('warning') ||
      value.includes('training')
    ) {
      return 'row-icon-warning';
    }

    return 'row-icon-info';
  }

  healthTextClass(value: number | null | undefined): string {
    const score = this.normalizePercent(value);

    if (score >= 70) {
      return 'pkpi-good';
    }

    if (score >= 40) {
      return 'pkpi-warn';
    }

    return 'pkpi-bad';
  }

  driftTextClass(value: number | null | undefined): string {
    const drift = this.normalizePercent(value);

    if (drift <= 20) {
      return 'pkpi-good';
    }

    if (drift <= 50) {
      return 'pkpi-warn';
    }

    return 'pkpi-bad';
  }

  formatShortDate(value: string | null | undefined): string {
    if (!value) {
      return '-';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString('fr-FR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  private normalizePercent(value: number | null | undefined): number {
    if (value === null || value === undefined || Number.isNaN(value)) {
      return 0;
    }

    if (value < 0) {
      return 0;
    }

    if (value > 100) {
      return 100;
    }

    return value;
  }
}