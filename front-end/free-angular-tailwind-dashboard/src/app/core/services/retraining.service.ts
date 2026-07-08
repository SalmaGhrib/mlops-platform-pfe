import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';

export type RetrainingFrequencyType = 'DAY' | 'WEEK' | 'MONTH';

export type RetrainingStrategy =
  | 'SCHEDULED_ONLY'
  | 'SCHEDULED_WITH_MONITORING'
  | 'SMART_MONITORING_ONLY'
  | 'FULL_SMART';

export interface RetrainingConfigRequest {
  enabled: boolean;
  frequencyType: RetrainingFrequencyType;
  frequencyValue: number;

  strategy?: RetrainingStrategy;
  failureMonitoringEnabled?: boolean;
  performanceMonitoringEnabled?: boolean;
  dataDriftMonitoringEnabled?: boolean;
  monitoringLookbackHours?: number;
  minPredictionsForMonitoring?: number;
  maxPredictionErrorRatePercent?: number;
  dataDriftThresholdPercent?: number;
}

export interface RetrainingConfigResponse {
  id: number;
  projectId: number;
  sourceExperimentId: number | null;
  enabled: boolean;
  frequencyType: RetrainingFrequencyType;
  frequencyValue: number;
  lastRetrainingAt: string | null;
  nextRetrainingAt: string | null;
  running: boolean;
  createdAt: string;
  updatedAt: string;

  strategy?: RetrainingStrategy | null;
  failureMonitoringEnabled?: boolean | null;
  performanceMonitoringEnabled?: boolean | null;
  dataDriftMonitoringEnabled?: boolean | null;
  monitoringLookbackHours?: number | null;
  minPredictionsForMonitoring?: number | null;
  maxPredictionErrorRatePercent?: number | null;
  dataDriftThresholdPercent?: number | null;
  lastTriggerReason?: string | null;
  lastDecisionReason?: string | null;
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

  forceNow(projectId: number): Observable<RetrainingConfigResponse> {
    return this.http.post<RetrainingConfigResponse>(
      `${this.apiUrl}/projects/${projectId}/force-now`,
      {},
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
