import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface GitHubRetrainingConfigDto {
  projectId: number | null;
  enabled: boolean;
  repository: string | null;
  branch: string | null;
  keyConfigured: boolean;
  secretSuffix: string | null;
  lastCommitSha: string | null;
  lastTriggerAt: string | null;
  lastTriggerStatus: string | null;
  lastTriggerMessage: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface GitHubConfigRequest {
  enabled: boolean;
  repository: string;
  branch: string;
}

export interface GitHubKeyResponse {
  secretKey: string;
  secretSuffix: string;
}

export interface GitHubWorkflowResponse {
  workflow: string;
}

export interface GitHubTriggerResponse {
  status: string | null;
  code: string | null;
  projectId: number | null;
  repository: string | null;
  branch: string | null;
  commitSha: string | null;
  experimentId: number | null;
  message: string | null;
}

@Injectable({
  providedIn: 'root',
})
export class GitHubRetrainingService {
  private readonly apiUrl = '/api/retraining';

  constructor(private http: HttpClient) {}

  getConfig(projectId: number): Observable<GitHubRetrainingConfigDto> {
    return this.http.get<GitHubRetrainingConfigDto>(
      `${this.apiUrl}/github/projects/${projectId}/config`,
      { headers: this.getAuthHeaders() }
    );
  }

  saveConfig(
    projectId: number,
    request: GitHubConfigRequest
  ): Observable<GitHubRetrainingConfigDto> {
    return this.http.put<GitHubRetrainingConfigDto>(
      `${this.apiUrl}/github/projects/${projectId}/config`,
      request,
      { headers: this.getAuthHeaders() }
    );
  }

  generateKey(projectId: number): Observable<GitHubKeyResponse> {
    return this.http.post<GitHubKeyResponse>(
      `${this.apiUrl}/github/projects/${projectId}/key`,
      {},
      { headers: this.getAuthHeaders() }
    );
  }

  revokeKey(projectId: number): Observable<GitHubRetrainingConfigDto> {
    return this.http.delete<GitHubRetrainingConfigDto>(
      `${this.apiUrl}/github/projects/${projectId}/key`,
      { headers: this.getAuthHeaders() }
    );
  }

  getWorkflow(projectId: number): Observable<GitHubWorkflowResponse> {
    return this.http.get<GitHubWorkflowResponse>(
      `${this.apiUrl}/github/projects/${projectId}/workflow`,
      { headers: this.getAuthHeaders() }
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