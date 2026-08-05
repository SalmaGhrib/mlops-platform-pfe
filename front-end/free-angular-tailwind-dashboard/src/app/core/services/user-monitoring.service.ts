import { Injectable } from '@angular/core';
import {
  HttpClient,
  HttpHeaders,
  HttpParams
} from '@angular/common/http';
import {
  Observable,
  throwError
} from 'rxjs';


export interface UserMonitoringProjectOption {
  id: number;
  name: string;
  dataType: string;
  projectType: string;
}

export interface UserMonitoringSummary {
  totalProjects: number;
  totalExperiments: number;
  completedRuns: number;
  failedRuns: number;
  runningRuns: number;
  totalDeployments: number;
  successfulDeployments: number;
  failedDeployments: number;
  deploymentsInProgress: number;
  runSuccessRate: number;
  deploymentSuccessRate: number;
  activeAlerts: number;
  averagePredictionLatencyMs: number;
}

export interface UserMonitoringScorePoint {
  experimentId: number;
  experimentName: string;
  modelVersion: string;
  primaryMetric: string;
  primaryMetricValue: number | null;
  globalScore: number | null;
  status: string;
  production: boolean;
  createdAt: string;
}

export interface UserMonitoringDeploymentItem {
  deploymentId: number;
  registeredModelName: string;
  modelVersion: string;
  deploymentStatus: string;
  active: boolean;
  endpoint: string | null;
  deployedAt: string | null;
  createdAt: string | null;
}

export interface UserMonitoringAlertItem {
  severity: string;
  title: string;
  message: string;
  source: string;
}

export interface UserMonitoringActivityItem {
  date: string | null;
  title: string;
  description: string;
  status: string;
}

export interface UserMonitoringProjectDashboard {
  projectId: number;
  projectName: string;
  description: string | null;
  dataType: string;
  projectType: string;
  targetColumn: string | null;
  runtimeMode: string;
  createdAt: string | null;

  metricPriority: string[];

  experimentsCount: number;
  completedRuns: number;
  failedRuns: number;
  runningRuns: number;

  runSuccessRate: number;
  deploymentSuccessRate: number;

  bestGlobalScore: number | null;
  currentProductionVersion: string | null;
  currentProductionModel: string | null;

  retrainingEnabled: boolean;
  retrainingStrategy: string;
  nextRetrainingAt: string | null;
  lastRetrainingAt: string | null;
  lastMonitoringCheckAt: string | null;
  lastDecisionReason: string | null;
  failureMonitoringEnabled: boolean;
  performanceMonitoringEnabled: boolean;
  dataDriftMonitoringEnabled: boolean;

  recentPredictionCount: number;
  predictionErrorRatePercent: number;
  dataDriftScorePercent: number;
  averagePredictionLatencyMs: number;
  predictionSuccessRate: number;
  lastPredictionAt: string | null;

  healthScore: number;

  scoreHistory: UserMonitoringScorePoint[];
  deployments: UserMonitoringDeploymentItem[];
  alerts: UserMonitoringAlertItem[];
  activities: UserMonitoringActivityItem[];
}

export interface UserMonitoringDashboardResponse {
  projects: UserMonitoringProjectOption[];
  summary: UserMonitoringSummary;
  projectDashboard: UserMonitoringProjectDashboard | null;
}


@Injectable({
  providedIn: 'root'
})
export class UserMonitoringService {

  private readonly apiUrl = '/api/user-monitoring';

  constructor(
    private readonly http: HttpClient
  ) {}

  /**
   * Charge le dashboard de monitoring de l'utilisateur connecté.
   */
  getDashboard(
    projectId?: number | null
  ): Observable<UserMonitoringDashboardResponse> {

    const token = this.getAccessToken();

    if (!token) {
      console.error(
        '[USER_MONITORING] Aucun token JWT trouvé dans le navigateur.'
      );

      return throwError(
        () => new Error(
          'Session utilisateur introuvable ou expirée. Veuillez vous reconnecter.'
        )
      );
    }

    let params = new HttpParams();

    if (projectId !== null && projectId !== undefined) {
      params = params.set(
        'projectId',
        String(projectId)
      );
    }

    const headers = new HttpHeaders({
      Authorization: this.normalizeBearerToken(token),
      Accept: 'application/json'
    });

    console.log(
      '[USER_MONITORING] Chargement du dashboard avec authentification.'
    );

    return this.http.get<UserMonitoringDashboardResponse>(
      `${this.apiUrl}/dashboard`,
      {
        headers,
        params
      }
    );
  }

  /**
   * Récupère le JWT selon les clés réellement utilisées par l'application.
   */
  private getAccessToken(): string | null {

    const possibleKeys = [
      'mlops_token',
      'token',
      'access_token',
      'accessToken',
      'authToken',
      'auth_token',
      'jwt',
      'jwtToken'
    ];

    /*
     * L'application stocke actuellement le token dans sessionStorage
     * sous la clé mlops_token.
     */
    for (const key of possibleKeys) {

      const sessionValue = sessionStorage.getItem(key);

      if (
        sessionValue &&
        sessionValue.trim().length > 0
      ) {
        return this.cleanStoredToken(sessionValue);
      }

      const localValue = localStorage.getItem(key);

      if (
        localValue &&
        localValue.trim().length > 0
      ) {
        return this.cleanStoredToken(localValue);
      }
    }

    /*
     * Compatibilité avec une session éventuellement stockée en JSON.
     */
    const possibleSessionKeys = [
      'mlops_user',
      'user',
      'currentUser',
      'auth',
      'authentication',
      'session'
    ];

    for (const key of possibleSessionKeys) {

      const sessionStorageValue = sessionStorage.getItem(key);
      const sessionToken = this.extractTokenFromJson(
        sessionStorageValue
      );

      if (sessionToken) {
        return sessionToken;
      }

      const localStorageValue = localStorage.getItem(key);
      const localToken = this.extractTokenFromJson(
        localStorageValue
      );

      if (localToken) {
        return localToken;
      }
    }

    return null;
  }

  /**
   * Extrait un token depuis un objet JSON stocké dans le navigateur.
   */
  private extractTokenFromJson(
    storedValue: string | null
  ): string | null {

    if (!storedValue) {
      return null;
    }

    try {
      const parsedValue: unknown = JSON.parse(storedValue);

      if (
        typeof parsedValue !== 'object' ||
        parsedValue === null
      ) {
        return null;
      }

      const record = parsedValue as Record<string, unknown>;

      const possibleProperties = [
        'mlops_token',
        'token',
        'access_token',
        'accessToken',
        'authToken',
        'auth_token',
        'jwt',
        'jwtToken'
      ];

      for (const property of possibleProperties) {
        const value = record[property];

        if (
          typeof value === 'string' &&
          value.trim().length > 0
        ) {
          return this.cleanStoredToken(value);
        }
      }

    } catch {
      /*
       * La valeur n'est pas un JSON valide.
       * Elle est ignorée sans faire planter la page.
       */
    }

    return null;
  }

  /**
   * Nettoie les guillemets ajoutés autour du token.
   */
  private cleanStoredToken(
    token: string
  ): string {

    return token
      .trim()
      .replace(/^"(.*)"$/, '$1');
  }

  /**
   * Ajoute Bearer sans le dupliquer.
   */
  private normalizeBearerToken(
    token: string
  ): string {

    const cleanedToken = this.cleanStoredToken(token);

    if (
      cleanedToken
        .toLowerCase()
        .startsWith('bearer ')
    ) {
      return cleanedToken;
    }

    return `Bearer ${cleanedToken}`;
  }
}