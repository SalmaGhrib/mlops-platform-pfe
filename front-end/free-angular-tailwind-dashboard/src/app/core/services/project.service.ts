import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  SearchPageResponse,
  SmartSearchParams
} from '../../shared/components/common/smart-search/smart-search.types';

export type ProjectType = 'classification' | 'regression';
export type RuntimeMode = 'STANDARD_RUNTIME' | 'CUSTOM_DOCKERFILE';
export type DataType = 'TABULAR' | 'TEXT' | 'IMAGE';

export interface MlProjectRequest {
  name: string;
  description?: string | null;
  targetColumn?: string | null;

  projectType: ProjectType;
  dataType?: DataType | string;
  data_type?: DataType | string;
  taskType?: string | null;

  primaryMetric1: string;
  primaryMetric2?: string | null;
  primaryMetric3?: string | null;

  autoPromoteBestModel?: boolean;
  runtimeMode?: RuntimeMode;
}

export interface MlProjectMetricsRequest {
  primaryMetric1: string;
  primaryMetric2?: string | null;
  primaryMetric3?: string | null;
  autoPromoteBestModel?: boolean;
}

export interface MlProjectResponse {
  id: number;
  name: string;
  description?: string | null;

  dataType?: DataType | string;
  data_type?: DataType | string;
  taskType?: string | null;

  projectType: ProjectType;
  targetColumn?: string | null;

  primaryMetric1: string;
  primaryMetric2?: string | null;
  primaryMetric3?: string | null;

  autoPromoteBestModel?: boolean;
  createdAt?: string;

  runtimeMode?: RuntimeMode | string;
  customImageUri?: string | null;
  dockerfilePath?: string | null;
  requirementsPath?: string | null;

  [key: string]: any;
}

export interface DeleteProjectResponse {
  projectId?: number;
  message?: string;
  deletedExperimentsCount?: number;
  success?: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class ProjectService {
  private readonly apiUrl = '/api/projects';

  constructor(private http: HttpClient) {}

  getMyProjects(): Observable<MlProjectResponse[]> {
    return this.http
      .get<MlProjectResponse[]>(this.apiUrl, {
        headers: this.getAuthHeaders()
      })
      .pipe(catchError(this.handleError));
  }

  searchProjects(params: SmartSearchParams): Observable<SearchPageResponse<MlProjectResponse>> {
    let httpParams = new HttpParams();

    if (params.q) {
      httpParams = httpParams.set('q', params.q);
    }
    if (params.projectType) {
      httpParams = httpParams.set('projectType', params.projectType);
    }
    if (params.dataType) {
      httpParams = httpParams.set('dataType', params.dataType);
    }
    httpParams = httpParams.set('page', String(params.page));
    httpParams = httpParams.set('size', String(params.size));

    return this.http
      .get<SearchPageResponse<MlProjectResponse>>(`${this.apiUrl}/search`, {
        headers: this.getAuthHeaders(),
        params: httpParams
      })
      .pipe(catchError(this.handleError));
  }

  getProjectById(projectId: number): Observable<MlProjectResponse> {
    return this.http
      .get<MlProjectResponse>(`${this.apiUrl}/${projectId}`, {
        headers: this.getAuthHeaders()
      })
      .pipe(catchError(this.handleError));
  }

  createProject(payload: MlProjectRequest): Observable<MlProjectResponse> {
    return this.http
      .post<MlProjectResponse>(this.apiUrl, payload, {
        headers: this.getAuthHeaders()
      })
      .pipe(catchError(this.handleError));
  }

  updateProject(
    projectId: number,
    payload: Partial<MlProjectRequest>
  ): Observable<MlProjectResponse> {
    return this.http
      .put<MlProjectResponse>(`${this.apiUrl}/${projectId}`, payload, {
        headers: this.getAuthHeaders()
      })
      .pipe(catchError(this.handleError));
  }

  updateProjectMetrics(
    projectId: number,
    payload: MlProjectMetricsRequest
  ): Observable<MlProjectResponse> {
    return this.http
      .put<MlProjectResponse>(`${this.apiUrl}/${projectId}/metrics`, payload, {
        headers: this.getAuthHeaders()
      })
      .pipe(catchError(this.handleError));
  }

  deleteProject(projectId: number): Observable<DeleteProjectResponse> {
    return this.http
      .delete<DeleteProjectResponse>(`${this.apiUrl}/${projectId}`, {
        headers: this.getAuthHeaders()
      })
      .pipe(catchError(this.handleError));
  }

  getMetricOptions(projectType: ProjectType | string): Observable<string[]> {
    const cleanProjectType = projectType || 'classification';

    return this.http
      .get<string[]>(`${this.apiUrl}/metric-options/${cleanProjectType}`, {
        headers: this.getAuthHeaders()
      })
      .pipe(catchError(this.handleError));
  }

  private getAuthHeaders(): HttpHeaders {
    const token = this.findJwtToken();

    let headers = new HttpHeaders({
      'Content-Type': 'application/json'
    });

    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }

    return headers;
  }

  private findJwtToken(): string | null {
    const directKeys = [
      'token',
      'jwt',
      'accessToken',
      'access_token',
      'authToken',
      'auth_token',
      'mlops_token',
      'currentUser',
      'user',
      'auth'
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

  private handleError(error: HttpErrorResponse) {
    let message = 'Une erreur est survenue.';

    if (error.error?.message) {
      message = error.error.message;
    } else if (error.error?.detail) {
      message =
        typeof error.error.detail === 'string'
          ? error.error.detail
          : JSON.stringify(error.error.detail);
    } else if (typeof error.error === 'string') {
      message = error.error;
    } else if (error.message) {
      message = error.message;
    }

    return throwError(() => new Error(message));
  }
}
