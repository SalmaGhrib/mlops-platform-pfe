import { Injectable } from '@angular/core';
import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
} from '@angular/common/http';
import { Observable, map, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

export type PredictionInputType = 'TABULAR' | 'IMAGE' | 'TEXT';

export interface PredictionRequest {
  rows: Record<string, unknown>[];
  modelName: string;
  modelAlias?: string;
}

export interface TextPredictionRequest {
  text: string;
  modelName: string;
  modelAlias?: string;
}

export interface PredictionResponse {
  status?: string;
  modelUri?: string;
  modelName?: string;
  modelAlias?: string;
  rowsCount?: number;
  predictionMode?: string;
  inputColumns?: string[];
  predictions?: unknown[];
  rawResponse?: unknown;

  [key: string]: unknown;
}

export interface ProductionModel {
  experimentId?: number | null;
  projectId?: number | null;
  projectName?: string | null;

  registeredModelName: string;
  productionVersion?: string | null;
  modelVersion?: string | null;

  scoreGlobal?: number | null;
  globalScore?: number | null;

  primaryMetric?: string | null;
  primaryMetricValue?: number | null;

  dataType?: PredictionInputType | string | null;
  taskType?: string | null;
  inputType?: string | null;

  deployed?: boolean;
  deploymentId?: number | null;
  deploymentStatus?: string | null;
  predictionEndpoint?: string | null;
  active?: boolean;

  visibility?: string | null;
  apiEnabled?: boolean | null;
  apiKey?: string | null;

  deploymentName?: string | null;
  serviceName?: string | null;

  createdAt?: string | null;
  updatedAt?: string | null;
  deployedAt?: string | null;

  [key: string]: unknown;
}

@Injectable({
  providedIn: 'root',
})
export class PredictionService {
  private readonly apiUrl = 'http://localhost:8081/api/predictions';

  /*
   * Important :
   * Prediction Test doit afficher TOUS les modèles utilisables par l'utilisateur connecté :
   * - ses propres modèles déployés
   * - les modèles PUBLIC_API
   * - les modèles SHARED avec lui
   *
   * Donc on charge les modèles depuis :
   * /api/model-deployments/visible
   */
  private readonly deploymentApiUrl = 'http://localhost:8081/api/model-deployments';

  constructor(private http: HttpClient) {}

  getMyProductionModels(): Observable<ProductionModel[]> {
    return this.http
      .get<ProductionModel[]>(`${this.deploymentApiUrl}/visible`, {
        headers: this.getAuthHeaders(false),
      })
      .pipe(
        map((models) =>
          (models || [])
            .map((model) => this.normalizeModel(model))
            .filter((model) => this.isDeployedModel(model))
        ),
        catchError(this.handleError)
      );
  }

  /*
   * Ancien endpoint gardé si tu veux le tester plus tard.
   * La page Prediction Test ne doit pas l'utiliser comme source principale.
   */
  getAllProductionModelsFromPredictionApi(): Observable<ProductionModel[]> {
    return this.http
      .get<ProductionModel[]>(`${this.apiUrl}/my-production-models`, {
        headers: this.getAuthHeaders(false),
      })
      .pipe(
        map((models) => (models || []).map((model) => this.normalizeModel(model))),
        catchError(this.handleError)
      );
  }

  predict(request: PredictionRequest): Observable<PredictionResponse> {
    return this.http
      .post<PredictionResponse>(`${this.apiUrl}/predict`, request, {
        headers: this.getAuthHeaders(true),
      })
      .pipe(catchError(this.handleError));
  }

  predictTabular(request: PredictionRequest): Observable<PredictionResponse> {
    return this.predict(request);
  }

  predictText(request: TextPredictionRequest): Observable<PredictionResponse> {
    return this.http
      .post<PredictionResponse>(`${this.apiUrl}/predict-text`, request, {
        headers: this.getAuthHeaders(true),
      })
      .pipe(catchError(this.handleError));
  }

  predictImage(
    modelName: string,
    imageFile: File,
    modelAlias: string = 'production'
  ): Observable<PredictionResponse> {
    const formData = new FormData();

    formData.append('modelName', modelName);
    formData.append('modelAlias', modelAlias);
    formData.append('imageFile', imageFile);

    return this.http
      .post<PredictionResponse>(`${this.apiUrl}/predict-image`, formData, {
        headers: this.getAuthHeaders(false),
      })
      .pipe(catchError(this.handleError));
  }

  private normalizeModel(raw: any): ProductionModel {
    const registeredModelName =
      raw.registeredModelName ??
      raw.registered_model_name ??
      raw.modelName ??
      raw.model_name ??
      '';

    const productionVersion =
      raw.productionVersion ??
      raw.production_version ??
      raw.modelVersion ??
      raw.model_version ??
      raw.latestVersion ??
      raw.latest_version ??
      '';

    const deploymentStatus =
      raw.deploymentStatus ??
      raw.deployment_status ??
      null;

    const predictionEndpoint =
      raw.predictionEndpoint ??
      raw.prediction_endpoint ??
      null;

    const deployed =
      raw.deployed === true ||
      raw.isDeployed === true ||
      raw.is_deployed === true ||
      String(deploymentStatus || '').trim().toUpperCase() === 'DEPLOYED';

    const dataType =
      raw.dataType ??
      raw.data_type ??
      raw.inputType ??
      raw.input_type ??
      raw.projectDataType ??
      raw.project_data_type ??
      'TABULAR';

    return {
      ...raw,

      id: raw.id ?? null,

      experimentId:
        raw.experimentId ??
        raw.experiment_id ??
        raw.productionExperimentId ??
        raw.production_experiment_id ??
        raw.latestExperimentId ??
        raw.latest_experiment_id ??
        null,

      projectId:
        raw.projectId ??
        raw.project_id ??
        null,

      projectName:
        raw.projectName ??
        raw.project_name ??
        '-',

      registeredModelName,
      productionVersion,

      modelVersion:
        raw.modelVersion ??
        raw.model_version ??
        productionVersion,

      scoreGlobal:
        raw.scoreGlobal ??
        raw.score_global ??
        raw.globalScore ??
        raw.global_score ??
        raw.productionGlobalScore ??
        raw.production_global_score ??
        null,

      globalScore:
        raw.globalScore ??
        raw.global_score ??
        raw.scoreGlobal ??
        raw.score_global ??
        null,

      primaryMetric:
        raw.primaryMetric ??
        raw.primary_metric ??
        raw.productionPrimaryMetric ??
        raw.production_primary_metric ??
        null,

      primaryMetricValue:
        raw.primaryMetricValue ??
        raw.primary_metric_value ??
        raw.productionPrimaryMetricValue ??
        raw.production_primary_metric_value ??
        null,

      dataType,
      inputType: raw.inputType ?? raw.input_type ?? dataType,

      deployed,

      deploymentId:
        raw.deploymentId ??
        raw.deployment_id ??
        raw.id ??
        null,

      deploymentStatus,
      predictionEndpoint,

      active:
        raw.active === true ||
        raw.active === 1 ||
        raw.active === '1' ||
        raw.active === 'true',

      visibility:
        raw.visibility ??
        raw.deploymentVisibility ??
        raw.deployment_visibility ??
        null,

      apiEnabled:
        raw.apiEnabled ??
        raw.api_enabled ??
        null,

      apiKey:
        raw.apiKey ??
        raw.api_key ??
        null,

      deploymentName:
        raw.deploymentName ??
        raw.deployment_name ??
        null,

      serviceName:
        raw.serviceName ??
        raw.service_name ??
        null,

      createdAt:
        raw.createdAt ??
        raw.created_at ??
        null,

      updatedAt:
        raw.updatedAt ??
        raw.updated_at ??
        null,

      deployedAt:
        raw.deployedAt ??
        raw.deployed_at ??
        null,
    };
  }

  private isDeployedModel(model: ProductionModel): boolean {
    const status = String(model.deploymentStatus || '')
      .trim()
      .toUpperCase();

    const endpoint = String(model.predictionEndpoint || '')
      .trim();

    return (
      !!model.registeredModelName &&
      model.active === true &&
      status === 'DEPLOYED' &&
      endpoint !== '' &&
      endpoint !== '-' &&
      endpoint.toLowerCase() !== 'null' &&
      endpoint.toLowerCase() !== 'undefined'
    );
  }

  private getAuthHeaders(includeJsonContentType: boolean): HttpHeaders {
    const token = this.findJwtToken();

    let headers = new HttpHeaders();

    if (includeJsonContentType) {
      headers = headers.set('Content-Type', 'application/json');
    }

    if (token) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    } else {
      console.warn('[PredictionService] No JWT token found in storage.');
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
        console.log(`[PredictionService] Token found in storage key: ${key}`);
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
    } else if (error.error?.error) {
      message = error.error.error;
    } else if (typeof error.error === 'string') {
      message = error.error;
    } else if (error.message) {
      message = error.message;
    }

    console.error('[PredictionService] API error:', error);

    return throwError(() => new Error(message));
  }
}