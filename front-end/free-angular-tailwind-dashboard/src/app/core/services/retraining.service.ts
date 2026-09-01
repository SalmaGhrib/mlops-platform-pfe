import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';

export type RetrainingStrategy =
  | 'MONITORING_SMART'
  | 'GITHUB_ACTIONS';

export interface RetrainingConfigRequest {
  enabled: boolean;

  strategy?: RetrainingStrategy;

  monitoringLookbackHours?: number;
  minPredictionsForMonitoring?: number;
  performanceDropThresholdPercent?: number;
  dataDriftThresholdPercent?: number;
}

export interface RetrainingConfigResponse {
  id: number;
  projectId: number;
  sourceExperimentId: number | null;
  enabled: boolean;
  lastRetrainingAt: string | null;
  running: boolean;
  createdAt: string;
  updatedAt: string;

  strategy?: RetrainingStrategy | null;
  monitoringLookbackHours?: number | null;
  minPredictionsForMonitoring?: number | null;
  performanceDropThresholdPercent?: number | null;
  dataDriftThresholdPercent?: number | null;
  manualTriggerRequested?: boolean | null;
  lastTriggerReason?: string | null;
  lastDecisionReason?: string | null;
  lastMonitoringCheckAt?: string | null;
  nextMonitoringCheckAt?: string | null;
  state?: string | null;
  cooldownUntil?: string | null;
  experimentId?: number | null;
  jobName?: string | null;
  kubeflowRunName?: string | null;
}

export interface ManualRetrainingResponse {
  success: boolean;
  projectId: number;
  experimentId: number | null;
  jobName: string | null;
  status: string | null;
  trigger: string | null;
  message: string | null;
  code: string | null;
}

export interface ProjectRetrainingStatusResponse {
  projectId: number;
  enabled: boolean;
  running: boolean;
  manualTriggerRequested: boolean;
  activeExperimentExists: boolean;
  status: string;
  message: string;
  nextRetrainingAt: string | null;
  lastRetrainingAt: string | null;
  lastDecisionReason: string | null;
  lastTriggerReason: string | null;
}

const RETRAINING_TERMINAL_STATUSES = ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'];

export interface ProjectRetrainingSummaryDTO {
  projectId: number;
  enabled: boolean;
  strategy: string;
  state: string;
  lastRetrainingAt?: string | null;
  lastMonitoringCheckAt?: string | null;
  nextMonitoringCheckAt?: string | null;
  lastTriggerReason?: string | null;
  running: boolean;
  cooldownUntil?: string | null;
}

export function isRetrainingTerminalStatus(status: string | null | undefined): boolean {
  return !!status && RETRAINING_TERMINAL_STATUSES.includes(String(status).toUpperCase());
}

export function isRetrainingNonTerminalStatus(status: string | null | undefined): boolean {
  return !!status && !isRetrainingTerminalStatus(status);
}

@Injectable({
  providedIn: 'root',
})
export class RetrainingService {
  private readonly apiUrl = '/api/retraining';

  constructor(private http: HttpClient) {}

  getConfig(projectId: number): Observable<RetrainingConfigResponse> {
    return this.http.get<RetrainingConfigResponse>(
      `${this.apiUrl}/projects/${projectId}/config`,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  getRetrainingSummary(
    projectId: number
  ): Observable<ProjectRetrainingSummaryDTO> {
    return this.http.get<ProjectRetrainingSummaryDTO>(
      `${this.apiUrl}/projects/${projectId}/summary`,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  saveProjectConfig(
    projectId: number,
    request: RetrainingConfigRequest
  ): Observable<RetrainingConfigResponse> {
    return this.http.post<RetrainingConfigResponse>(
      `${this.apiUrl}/projects/${projectId}/config`,
      request,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  saveConfig(
    projectId: number,
    experimentId: number,
    request: RetrainingConfigRequest
  ): Observable<RetrainingConfigResponse> {
    return this.http.post<RetrainingConfigResponse>(
      `${this.apiUrl}/projects/${projectId}/experiments/${experimentId}/config`,
      request,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  enableWeekly(
    projectId: number,
    experimentId: number
  ): Observable<RetrainingConfigResponse> {
    return this.http.post<RetrainingConfigResponse>(
      `${this.apiUrl}/projects/${projectId}/experiments/${experimentId}/enable-weekly`,
      {},
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  disable(projectId: number): Observable<RetrainingConfigResponse> {
    return this.http.post<RetrainingConfigResponse>(
      `${this.apiUrl}/projects/${projectId}/disable`,
      {},
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  forceNow(projectId: number): Observable<ManualRetrainingResponse> {
    return this.http.post<ManualRetrainingResponse>(
      `${this.apiUrl}/projects/${projectId}/force-now`,
      {},
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  getProjectStatus(
    projectId: number
  ): Observable<ProjectRetrainingStatusResponse> {
    return this.http.get<ProjectRetrainingStatusResponse>(
      `${this.apiUrl}/projects/${projectId}/status`,
      {
        headers: this.getAuthHeaders(),
      }
    );
  }

  private getAuthHeaders(): HttpHeaders {
    const token = this.findJwtToken();

    let headers = new HttpHeaders({
      'Content-Type': 'application/json',
    });

    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }

    return headers;
  }

  private findJwtToken(): string | null {
    const directKeys = [
      'mlops_token',
      'token',
      'jwt',
      'accessToken',
      'access_token',
      'authToken',
      'auth_token',
      'currentUser',
      'user',
      'auth',
    ];

    for (const key of directKeys) {
      const value = localStorage.getItem(key) || sessionStorage.getItem(key);
      const token = this.extractTokenFromValue(value);

      if (token) {
        return token;
      }
    }

    const localToken = this.scanStorageForJwt(localStorage);
    if (localToken) {
      return localToken;
    }

    const sessionToken = this.scanStorageForJwt(sessionStorage);
    if (sessionToken) {
      return sessionToken;
    }

    return null;
  }

  private scanStorageForJwt(storage: Storage): string | null {
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i);

      if (!key) {
        continue;
      }

      const value = storage.getItem(key);
      const token = this.extractTokenFromValue(value);

      if (token) {
        return token;
      }
    }

    return null;
  }

  private extractTokenFromValue(value: string | null): string | null {
    if (!value) {
      return null;
    }

    const cleanValue = value.trim();

    if (this.looksLikeJwt(cleanValue)) {
      return this.removeBearerPrefix(cleanValue);
    }

    try {
      const parsed = JSON.parse(cleanValue);

      const possibleToken =
        parsed?.token ||
        parsed?.jwt ||
        parsed?.accessToken ||
        parsed?.access_token ||
        parsed?.authToken ||
        parsed?.auth_token;

      if (typeof possibleToken === 'string' && this.looksLikeJwt(possibleToken)) {
        return this.removeBearerPrefix(possibleToken);
      }
    } catch {
      return null;
    }

    return null;
  }

  private removeBearerPrefix(token: string): string {
    return token.replace(/^Bearer\s+/i, '').trim();
  }

  private looksLikeJwt(value: string): boolean {
    const token = this.removeBearerPrefix(value);
    return token.startsWith('eyJ') && token.split('.').length === 3;
  }
}
