import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface DashboardRecentRun {
  experimentId: number | null;
  experimentName: string | null;
  projectName: string | null;
  status: string | null;
  modelName: string | null;
  primaryMetric: string | null;
  primaryMetricValue: number | null;
  registeredModelName: string | null;
  createdAt: string | null;
  finishedAt: string | null;
}

export interface DashboardBestModel {
  registeredModelName: string | null;
  modelName: string | null;
  projectName: string | null;
  globalScore: number | null;
  primaryMetric: string | null;
  primaryMetricValue: number | null;
  productionVersion: string | null;
  deploymentStatus: string | null;
  hasProduction: boolean | null;
}

export interface DashboardPlatformStatus {
  backend: string;
  database: string;
  latencyMs: number;
}

export interface DashboardSummary {
  projectsTotal: number;
  runningProjects: number;
  runsTotal: number;
  runsRunning: number;
  runsCompleted: number;
  runsFailed: number;
  runsCancelled: number;
  runSuccessRate: number;
  modelsTotal: number;
  modelsWithProduction: number;
  deploymentsTotal: number;
  deploymentsDeployed: number;
  deploymentsInProgress: number;
  deploymentsFailed: number;
  activeDeployments: number;
  recentRuns: DashboardRecentRun[];
  bestModels: DashboardBestModel[];
  platformStatus: DashboardPlatformStatus;
  evaluatedAt: string;
}

@Injectable({
  providedIn: 'root'
})
export class DashboardService {
  private readonly apiUrl = '/api/dashboard';

  constructor(private http: HttpClient) {}

  getSummary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${this.apiUrl}/summary`, {
      headers: this.getAuthHeaders()
    });
  }

  private getToken(): string | null {
    const keys = [
      'mlops_token',
      'token',
      'jwt',
      'accessToken',
      'access_token',
      'authToken',
      'auth_token'
    ];

    for (const key of keys) {
      const value = sessionStorage.getItem(key) || localStorage.getItem(key);
      const token = this.extractTokenFromValue(value);

      if (token) {
        return token;
      }
    }

    const fromLocal = this.scanStorageForToken(localStorage);
    if (fromLocal) {
      return fromLocal;
    }

    return this.scanStorageForToken(sessionStorage);
  }

  private scanStorageForToken(storage: Storage): string | null {
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i);

      if (!key) {
        continue;
      }

      const token = this.extractTokenFromValue(storage.getItem(key));

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

    const trimmed = value.trim();

    if (this.looksLikeJwt(trimmed)) {
      return this.removeBearerPrefix(trimmed);
    }

    try {
      const parsed = JSON.parse(trimmed);
      return this.extractTokenFromObject(parsed);
    } catch {
      return null;
    }
  }

  private extractTokenFromObject(value: any): string | null {
    if (!value) {
      return null;
    }

    if (typeof value === 'string') {
      return this.looksLikeJwt(value) ? this.removeBearerPrefix(value) : null;
    }

    const tokenKeys = [
      'token',
      'jwt',
      'accessToken',
      'access_token',
      'authToken',
      'auth_token'
    ];

    for (const key of tokenKeys) {
      if (value[key] && this.looksLikeJwt(String(value[key]))) {
        return this.removeBearerPrefix(String(value[key]));
      }
    }

    for (const key of Object.keys(value)) {
      const nested = value[key];

      if (nested && typeof nested === 'object') {
        const extracted = this.extractTokenFromObject(nested);

        if (extracted) {
          return extracted;
        }
      }
    }

    return null;
  }

  private looksLikeJwt(value: string): boolean {
    const token = this.removeBearerPrefix(value);
    return /^[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+$/.test(token);
  }

  private removeBearerPrefix(token: string): string {
    return token.replace(/^Bearer\s+/i, '').trim();
  }

  private getAuthHeaders(): HttpHeaders {
    const token = this.getToken();

    if (!token) {
      return new HttpHeaders();
    }

    return new HttpHeaders({
      Authorization: `Bearer ${token}`
    });
  }
}
