import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';

export type DeploymentVisibility = 'PRIVATE' | 'PUBLIC_API' | 'SHARED';

export interface UserSearchResult {
  id: number;
  email?: string | null;
  username?: string | null;
  name?: string | null;
  nom?: string | null;
  prenom?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  fullName?: string | null;

  [key: string]: any;
}

export interface ModelDeploymentShareResponse {
  id?: number;
  deploymentId?: number;
  userId?: number;
  sharedWithUserId?: number;
  email?: string | null;
  username?: string | null;
  name?: string | null;
  fullName?: string | null;
  permission?: string | null;

  [key: string]: any;
}

export interface ModelRegistryItem {
  projectId: number;
  projectName: string;

  registeredModelName: string;
  modelName?: string | null;

  latestVersion: string;
  productionVersion: string;

  latestExperimentId?: number | null;
  latestExperimentName?: string | null;

  productionExperimentId?: number | null;
  productionExperimentName?: string | null;

  experimentId?: number | null;
  experimentName?: string | null;

  productionRunId?: string | null;
  productionKubeflowRunId?: string | null;

  mlflowRunId?: string | null;
  kubeflowRunId?: string | null;

  productionModelName?: string | null;

  globalScore?: number | null;
  productionGlobalScore?: number | null;

  primaryMetric?: string | null;
  primaryMetricValue?: number | null;
  direction?: string | null;

  productionPrimaryMetric?: string | null;
  productionPrimaryMetricValue?: number | null;
  productionDirection?: string | null;

  hasProduction: boolean;
  status?: string | null;

  metrics?: Record<string, number>;
  productionMetrics?: Record<string, number>;

  detectedAlgorithms?: string | any[] | null;
  usedAlgorithms?: string | any[] | null;
  algorithmReplacements?: string | any[] | null;
  algorithmReplacementCount?: number | string | null;
  hasAlgorithmReplacement?: boolean | string | number | null;

  modelFileName?: string | null;
  datasetFileName?: string | null;

  source?: string | null;

  createdAt?: string | null;
  updatedAt?: string | null;

  deploymentStatus?: string | null;
  predictionEndpoint?: string | null;

  dockerImage?: string | null;
  errorMessage?: string | null;
  activeDeploymentId?: number | null;

  apiEnabled?: boolean | null;
  visibility?: DeploymentVisibility | string | null;
  apiKey?: string | null;
  apiKeyCreatedAt?: string | null;
  apiKeyLastUsedAt?: string | null;

  [key: string]: any;
}

export interface ModelDeploymentResponse {
  id: number;
  projectId: number;
  experimentId: number;

  registeredModelName: string;
  modelVersion: string;

  mlflowRunId?: string | null;
  kubeflowRunId?: string | null;

  deploymentStatus: string;
  predictionEndpoint?: string | null;

  deploymentName?: string | null;
  serviceName?: string | null;
  namespaceName?: string | null;

  dockerImage?: string | null;
  errorMessage?: string | null;

  active: boolean;

  deployedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;

  apiEnabled?: boolean | null;
  visibility?: DeploymentVisibility | string | null;
  apiKey?: string | null;
  apiKeyCreatedAt?: string | null;
  apiKeyLastUsedAt?: string | null;

  projectName?: string | null;
  productionVersion?: string | null;
  mlflowProductionVersion?: string | null;
  kubernetesActiveVersion?: string | null;

  externalEndpoint?: string | null;

  ownerId?: number | null;
  ownerEmail?: string | null;
  projectOwnerId?: number | null;
  projectOwnerEmail?: string | null;

  [key: string]: any;
}

export type AccessibleModel = ModelDeploymentResponse;

export interface ModelApiKeyItem {
  id: number;
  deploymentId?: number;
  ownerId?: number;
  name?: string | null;
  keyPrefix?: string | null;
  maskedKey?: string | null;
  apiKey?: string | null;
  enabled?: boolean | null;
  createdAt?: string | null;
  expiresAt?: string | null;
  revokedAt?: string | null;
  lastUsedAt?: string | null;
  dailyQuota?: number | null;
  requestsToday?: number | null;
  totalRequests?: number | null;
  usageDate?: string | null;
  quotaRemaining?: number | null;

  [key: string]: any;
}

export interface ModelApiKeyStats {
  totalToday?: number | null;
  successToday?: number | null;
  failedToday?: number | null;
  successRate?: number | null;
  avgLatencyMs?: number | null;
  dailyQuota?: number | null;
  quotaRemaining?: number | null;
  keys?: ModelApiKeyItem[] | null;

  [key: string]: any;
}

@Injectable({
  providedIn: 'root'
})
export class ModelRegistryService {
  private readonly apiUrl = '/api/model-registry';
  private readonly deploymentApiUrl = '/api/model-deployments';
  private readonly externalApiBaseUrl = '/api/external';
  private readonly usersApiUrl = '/api/users';

  constructor(private http: HttpClient) {}

  private getToken(): string | null {
    return (
      sessionStorage.getItem('mlops_token') ||
      localStorage.getItem('mlops_token') ||
      sessionStorage.getItem('token') ||
      localStorage.getItem('token') ||
      sessionStorage.getItem('jwt') ||
      localStorage.getItem('jwt') ||
      sessionStorage.getItem('accessToken') ||
      localStorage.getItem('accessToken') ||
      sessionStorage.getItem('authToken') ||
      localStorage.getItem('authToken')
    );
  }

  private getAuthHeaders(): HttpHeaders {
    const token = this.getToken();

    if (!token) {
      return new HttpHeaders();
    }

    const cleanToken = token.startsWith('Bearer ')
      ? token.substring(7)
      : token;

    return new HttpHeaders({
      Authorization: `Bearer ${cleanToken}`
    });
  }

  getMyModels(): Observable<ModelRegistryItem[]> {
    return this.http.get<ModelRegistryItem[]>(
      `${this.apiUrl}/my-models`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  syncMyModels(): Observable<ModelRegistryItem[]> {
    return this.http.post<ModelRegistryItem[]>(
      `${this.apiUrl}/my-models/sync`,
      {},
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  /*
   * Deployed Models :
   * uniquement les modèles appartenant au user connecté.
   */
  getOwnedDeployments(): Observable<ModelDeploymentResponse[]> {
    return this.http.get<ModelDeploymentResponse[]>(
      `${this.deploymentApiUrl}/owned`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  /*
   * Prediction Test :
   * tous les modèles utilisables par le user connecté :
   * ses modèles + PUBLIC_API + SHARED.
   */
  getVisibleDeployments(): Observable<ModelDeploymentResponse[]> {
    return this.http.get<ModelDeploymentResponse[]>(
      `${this.deploymentApiUrl}/visible`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  /*
   * Accessible Models :
   * uniquement les modèles DES AUTRES users :
   * PUBLIC_API + SHARED avec le user connecté.
   */
  getAccessibleModels(): Observable<AccessibleModel[]> {
    return this.http.get<AccessibleModel[]>(
      `${this.deploymentApiUrl}/accessible`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  updateDeploymentVisibility(
    deploymentId: number,
    visibility: DeploymentVisibility
  ): Observable<ModelDeploymentResponse> {
    return this.http.put<ModelDeploymentResponse>(
      `${this.deploymentApiUrl}/${deploymentId}/visibility`,
      { visibility },
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  deployModel(model: ModelRegistryItem): Observable<ModelDeploymentResponse> {
    console.warn(
      '[ModelRegistryService] Manual deployment is disabled. Deployment is now automatic.',
      model
    );

    return throwError(() => new Error(
      'Le déploiement manuel est désactivé. Le déploiement est maintenant automatique côté backend.'
    ));
  }

  getActiveDeploymentByProject(projectId: number): Observable<ModelDeploymentResponse> {
    return this.http.get<ModelDeploymentResponse>(
      `${this.deploymentApiUrl}/project/${projectId}/active`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  getDeploymentsByProject(projectId: number): Observable<ModelDeploymentResponse[]> {
    return this.http.get<ModelDeploymentResponse[]>(
      `${this.deploymentApiUrl}/project/${projectId}`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  getDeploymentsByExperiment(experimentId: number): Observable<ModelDeploymentResponse[]> {
    return this.http.get<ModelDeploymentResponse[]>(
      `${this.deploymentApiUrl}/experiment/${experimentId}`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  getExternalPredictionEndpoint(registeredModelName: string): string {
    const cleanName = encodeURIComponent(registeredModelName || '');
    return `${this.externalApiBaseUrl}/models/${cleanName}/predict`;
  }

  enableApiAccess(deploymentId: number): Observable<ModelDeploymentResponse> {
    return this.http.post<ModelDeploymentResponse>(
      `${this.deploymentApiUrl}/${deploymentId}/api/enable`,
      {},
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  disableApiAccess(deploymentId: number): Observable<ModelDeploymentResponse> {
    return this.http.post<ModelDeploymentResponse>(
      `${this.deploymentApiUrl}/${deploymentId}/api/disable`,
      {},
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  regenerateApiKey(deploymentId: number): Observable<ModelDeploymentResponse> {
    return this.http.post<ModelDeploymentResponse>(
      `${this.deploymentApiUrl}/${deploymentId}/api/regenerate-key`,
      {},
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  searchUsers(query: string, deploymentId?: number | null): Observable<UserSearchResult[]> {
    let params = new HttpParams().set('query', query || '');

    if (deploymentId) {
      params = params.set('deploymentId', String(deploymentId));
    }

    return this.http.get<UserSearchResult[]>(
      `${this.usersApiUrl}/search`,
      {
        headers: this.getAuthHeaders(),
        params
      }
    );
  }

  getDeploymentShares(deploymentId: number): Observable<ModelDeploymentShareResponse[]> {
    return this.http.get<ModelDeploymentShareResponse[]>(
      `${this.deploymentApiUrl}/${deploymentId}/shares`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  shareDeploymentWithUsers(
    deploymentId: number,
    userIds: number[]
  ): Observable<ModelDeploymentShareResponse[]> {
    return this.http.post<ModelDeploymentShareResponse[]>(
      `${this.deploymentApiUrl}/${deploymentId}/shares`,
      {
        userIds,
        permission: 'PREDICT_ONLY'
      },
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  removeDeploymentShare(
    deploymentId: number,
    userId: number
  ): Observable<void> {
    return this.http.delete<void>(
      `${this.deploymentApiUrl}/${deploymentId}/shares/${userId}`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  /*
   * =========================================================
   * SMART MODEL GATEWAY — gestion des clés API
   * =========================================================
   */

  private readonly gatewayKeysUrl = '/api/models';

  getApiKeys(modelId: number): Observable<ModelApiKeyItem[]> {
    return this.http.get<ModelApiKeyItem[]>(
      `${this.gatewayKeysUrl}/${modelId}/api-keys`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  createApiKey(
    modelId: number,
    body: {
      name?: string | null;
      dailyQuota?: number | null;
      expiresAt?: string | null;
    }
  ): Observable<ModelApiKeyItem> {
    return this.http.post<ModelApiKeyItem>(
      `${this.gatewayKeysUrl}/${modelId}/api-keys`,
      body || {},
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  getApiKeyStats(modelId: number): Observable<ModelApiKeyStats> {
    return this.http.get<ModelApiKeyStats>(
      `${this.gatewayKeysUrl}/${modelId}/api-keys/stats`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  enableApiKey(keyId: number): Observable<ModelApiKeyItem> {
    return this.http.patch<ModelApiKeyItem>(
      `/api/model-api-keys/${keyId}/enable`,
      {},
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  disableApiKey(keyId: number): Observable<ModelApiKeyItem> {
    return this.http.patch<ModelApiKeyItem>(
      `/api/model-api-keys/${keyId}/disable`,
      {},
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  revokeApiKey(keyId: number): Observable<any> {
    return this.http.delete<any>(
      `/api/model-api-keys/${keyId}`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  buildCurlForModel(registeredModelName: string, apiKey: string | null): string {
    const cleanName = encodeURIComponent(registeredModelName || '');
    const baseUrl = this.getPublicBaseUrl();
    const keyValue = apiKey || 'VOTRE_CLE_API';

    return [
      'curl.exe -X POST \\',
      `  "${baseUrl}/api/external/models/${cleanName}/predict" \\`,
      '  -H "X-API-KEY: VOTRE_CLE_API" \\',
      '  -H "Content-Type: application/json" \\',
      '  -d \'{ "feature_1": 1.2, "feature_2": 0.4 }\''
    ].join('\n');
  }

  getPublicBaseUrlForCopy(): string {
    return this.getPublicBaseUrl();
  }

  private getPublicBaseUrl(): string {
    const origin = window.location.origin;

    if (origin && origin !== 'http://localhost:4200' && origin !== 'http://127.0.0.1:4200') {
      return origin;
    }

    return 'http://128.251.112.197';
  }
}
