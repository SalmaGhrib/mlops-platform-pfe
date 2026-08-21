import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface MonitoringSummary {
  totalDeployments: number;
  activeProductionModels: number;

  completedRuns: number;
  failedRuns: number;
  runningRuns: number;

  successfulDeployments: number;
  failedDeployments: number;
  deploymentsInProgress: number;

  runSuccessRate: number;
  deploymentSuccessRate: number;

  unavailableModelDeployments: number;

  averageBackendLatencyMs: number;
  averageBackendLatencySeconds: number;

  averageRunDurationSeconds: number;
  maxRunDurationSeconds: number;
  lastRunDurationSeconds: number;

  averageStandardRunDurationSeconds: number;
  lastStandardRunDurationSeconds: number;

  averageDockerRunDurationSeconds: number;
  lastDockerRunDurationSeconds: number;

  standardRunsCount: number;
  dockerRunsCount: number;
}
export interface MonitoringAlert {
  id: number;
  alertType: string;
  severity: string;
  title: string;
  message: string;
  targetType: string;
  createdAt: string;
}

export interface AdminMonitoringOverview {
  totalProjects: number;
  monitoredProjects: number;
  totalDeployments: number;
  activeProductionModels: number;
  degradedModels: number;
  modelsBelowQualityThreshold: number;
  projectsWithDataDrift: number;
  projectsWithPredictionErrors: number;
  projectsWithPerformanceDrop: number;
  criticalAlerts24h: number;
  totalAlerts24h: number;
  recentAlerts: MonitoringAlert[];
  projects: AdminProjectHealthItem[];
}

export interface AdminProjectHealthItem {
  projectId: number;
  projectName: string;
  projectType: string;
  registeredModelName: string | null;
  modelVersion: string | null;
  qualityStatus: string;
  globalScore: number | null;
  globalScoreThreshold: number | null;
  predictionErrorRatePercent: number;
  dataDriftScorePercent: number;
  performanceDropPercent: number;
  latestAlertType: string | null;
  latestAlertSeverity: string | null;
  latestAlertMessage: string | null;
  latestAlertAt: string | null;
  retrainingEnabled: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class MonitoringService {
  private apiUrl = '/api/admin/monitoring';

  constructor(private http: HttpClient) {}

  getSummary(): Observable<MonitoringSummary> {
    return this.http.get<MonitoringSummary>(`${this.apiUrl}/summary`);
  }

  getOverview(): Observable<AdminMonitoringOverview> {
    return this.http.get<AdminMonitoringOverview>(`${this.apiUrl}/overview`);
  }
}
