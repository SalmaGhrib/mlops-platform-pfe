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
@Injectable({
  providedIn: 'root'
})
export class MonitoringService {
  private apiUrl = 'http://localhost:8081/api/admin/monitoring';

  constructor(private http: HttpClient) {}

  getSummary(): Observable<MonitoringSummary> {
    return this.http.get<MonitoringSummary>(`${this.apiUrl}/summary`);
  }
}