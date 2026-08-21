import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface MlProjectResponse {
  id: number;
  name: string;
  description?: string;
  projectType: 'classification' | 'regression';
  primaryMetric1: string;
  primaryMetric2: string;
  primaryMetric3: string;
  registeredModelName?: string;
  createdAt?: string;
  updatedAt?: string;

  runtimeMode?: 'STANDARD_RUNTIME' | 'CUSTOM_DOCKERFILE' | string;
  customImageUri?: string | null;
  dockerfilePath?: string | null;
  requirementsPath?: string | null;
}

export interface MlExperimentResponse {
  id: number;
  projectId: number;
  projectName: string;

  experimentName: string;
  status: string;

  modelName: string;
  modelType: string;

  primaryMetric: string;
  primaryMetricValue: number | null;
  direction: string;

  modelFileName: string;
  datasetFileName: string;

  registeredModelName: string;

  modelVersion: string | number | null;
  mlflowModelVersion?: string | number | null;

  latestVersion?: string | number | null;
  latest_version?: string | number | null;

  globalScore: number | null;

  productionVersion?: string | number | null;
  productionModelVersion?: string | number | null;

  production?: boolean;
  isProduction?: boolean;

  kubeflowRunId?: string;
  kubeflowRunName?: string;

  mlflowExperimentName?: string;
  mlflowExperimentId?: string | number | null;
  mlflowRunId?: string;

  metrics?: Record<string, number>;

  detectedAlgorithms?: string | any[] | null;
  detected_algorithms?: string | any[] | null;

  usedAlgorithms?: string | any[] | null;
  used_algorithms?: string | any[] | null;

  algorithmReplacements?: string | any[] | null;
  algorithm_replacements?: string | any[] | null;

  algorithmReplacementCount?: number | string | null;
  algorithm_replacement_count?: number | string | null;

  hasAlgorithmReplacement?: boolean | string | number | null;
  has_algorithm_replacement?: boolean | string | number | null;

  runtimeMode?: string | null;
  runtime_mode?: string | null;

  customImageUri?: string | null;
  custom_image_uri?: string | null;

  dockerfilePath?: string | null;
  dockerfile_path?: string | null;

  requirementsPath?: string | null;
  requirements_path?: string | null;

  buildStatus?: string | null;
  build_status?: string | null;

  buildLogs?: string | null;
  build_logs?: string | null;

  message: string;
  errorMessage: string;

  createdAt: string;
  updatedAt?: string;
  startedAt?: string | null;
  finishedAt?: string | null;

  [key: string]: any;
}

export type PipelineStepStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';

export interface RunPipelineStepDto {
  stepCode: string;
  label?: string;
  executionMode?: string;
  status: PipelineStepStatus;
  progress?: number | null;
  orderIndex?: number;
  startedAt?: string | null;
  completedAt?: string | null;
  message?: string | null;
  errorCode?: string | null;
  errorDetails?: string | null;
  jobName?: string | null;
  podName?: string | null;
  externalId?: string | null;
  updatedAt?: string | null;
  hasLogs?: boolean;

  [key: string]: any;
}

export interface PipelineStatusResponse {
  experimentId?: number;
  runtimeMode?: string;
  runStatus?: string | null;
  kubeflowStatus?: string | null;
  buildStatus?: string | null;
  globalProgress?: number | null;
  updatedAt?: string | null;
  steps: RunPipelineStepDto[];
}

export interface ReplacementOption {
  className: string;
  display: string;
  module: string;
  modelType?: 'classification' | 'regression' | string;
}

export interface DetectedAlgorithmResponse {
  id?: string;
  occurrence_id?: string;
  occurrenceId?: string;

  var_name?: string;
  varName?: string;
  variable?: string;

  original_class?: string;
  originalClass?: string;
  canonical_class?: string;
  canonicalClass?: string;

  class_name?: string;
  className?: string;
  class?: string;
  name?: string;
  algorithm?: string;
  display?: string;
  originalDisplay?: string;
  original_display?: string;

  module?: string;
  library?: string;
  lib?: string;

  model_type?: string;
  modelType?: string;
  problem_type?: string;
  problemType?: string;
  type?: string;

  supported?: boolean;

  line?: number;
  column?: number;

  replacement_options?: ReplacementOption[];
  replacementOptions?: ReplacementOption[];
  available_replacements?: ReplacementOption[];
  availableReplacements?: ReplacementOption[];

  [key: string]: any;
}

export interface AnalyzeModelFileResponse {
  status?: string;
  filename?: string;

  analysis?: any;

  error?: string;
  details?: string;

  detected_algorithms?: DetectedAlgorithmResponse[];
  detectedAlgorithms?: DetectedAlgorithmResponse[];
  algorithms?: DetectedAlgorithmResponse[];

  replaceable_algorithms?: DetectedAlgorithmResponse[];
  replaceableAlgorithms?: DetectedAlgorithmResponse[];

  model_occurrences?: DetectedAlgorithmResponse[];
  modelOccurrences?: DetectedAlgorithmResponse[];

  supported_modules?: any[];
  supportedModules?: any[];

  unsupported_modules?: any[];
  unsupportedModules?: any[];

  warnings?: string[];
  summary?: string;
  message?: string;

  [key: string]: any;
}

export interface RuntimeModuleAnalysis {
  name: string;
  supported: boolean;
  source?: string;
  packageName?: string;
  message?: string;
}

export interface RuntimeAlgorithmAnalysis {
  name: string;
  category?: string;
  supported: boolean;
  replaceable: boolean;
  message?: string;
  module?: string;
  variable?: string;
  line?: number;
}

export interface RuntimeAnalysisResult {
  runtimeCompatible: boolean;
  runtime_compatible?: boolean;
  compatible?: boolean;
  status?: string;
  message?: string;
  summary?: string;
  detectedImports?: string[];
  supportedImports?: string[];
  unsupportedImports?: string[];
  detectedMetrics?: string[];
  detectedModules: RuntimeModuleAnalysis[];
  supportedModules?: RuntimeModuleAnalysis[];
  unsupportedModules: RuntimeModuleAnalysis[] | string[];
  detectedLibraries?: any[];
  supportedLibraries?: any[];
  unsupportedLibraries?: any[];
  missingDependencies?: string[];
  detectedAlgorithms: RuntimeAlgorithmAnalysis[];
  replaceableAlgorithms?: RuntimeAlgorithmAnalysis[];
  warnings?: string[];
  recommendation?: string;
}

export interface AlgorithmReplacementPayload {
  occurrence_id?: string;
  var_name: string | null;
  original_class: string;
  new_class: string;
  new_lib: string;
}

export interface UploadAndRunCustomModelRequest {
  projectId: number;
  projectName: string;
  modelName: string;
  runName: string;
  primaryMetric: string;
  direction: 'min' | 'max';
  registeredModelName: string;
  modelFile: File;
  datasetFile: File | null;
  algorithmReplacements: AlgorithmReplacementPayload[];
  useOriginalScript?: boolean;
  algorithmInjectionEnabled?: boolean;
  replacementEnabled?: boolean;

  runtimeMode?: 'STANDARD_RUNTIME' | 'CUSTOM_DOCKERFILE' | string;
  dockerfileFile?: File | null;
  requirementsFile?: File | null;
  customImageUri?: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class ExperimentService {
  private readonly springApiUrl = '/api/experiments';
  private readonly projectApiUrl = '/api/projects';
  private readonly runtimeApiUrl = '/api/runtime';

  constructor(private http: HttpClient) {}

  private getToken(): string | null {
    const possibleKeys = [
      'mlops_token',
      'token',
      'jwt',
      'accessToken',
      'access_token',
      'authToken',
      'auth_token',
      'currentUser',
      'user',
      'auth'
    ];

    for (const key of possibleKeys) {
      const directValue = sessionStorage.getItem(key) || localStorage.getItem(key);
      const extracted = this.extractTokenFromValue(directValue);

      if (extracted) {
        return extracted;
      }
    }

    const fromLocal = this.scanStorageForToken(localStorage);
    if (fromLocal) {
      return fromLocal;
    }

    const fromSession = this.scanStorageForToken(sessionStorage);
    if (fromSession) {
      return fromSession;
    }

    return null;
  }

  private scanStorageForToken(storage: Storage): string | null {
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i);

      if (!key) {
        continue;
      }

      const value = storage.getItem(key);
      const extracted = this.extractTokenFromValue(value);

      if (extracted) {
        return extracted;
      }
    }

    return null;
  }

  private extractTokenFromValue(value: string | null): string | null {
    if (!value) {
      return null;
    }

    const trimmedValue = value.trim();

    if (this.looksLikeJwt(trimmedValue)) {
      return trimmedValue;
    }

    try {
      const parsed = JSON.parse(trimmedValue);
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
      return this.looksLikeJwt(value) ? value : null;
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
        return String(value[key]);
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
    return /^[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+$/.test(value);
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

  getProjects(): Observable<MlProjectResponse[]> {
    return this.http.get<MlProjectResponse[]>(
      this.projectApiUrl,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  getExperimentsByProject(projectId: number): Observable<MlExperimentResponse[]> {
    return this.http.get<MlExperimentResponse[]>(
      `${this.springApiUrl}/project/${projectId}`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  syncProjectResults(projectId: number): Observable<MlExperimentResponse[]> {
    return this.http.post<MlExperimentResponse[]>(
      `${this.springApiUrl}/project/${projectId}/sync-results`,
      {},
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  getPipelineStatus(experimentId: number): Observable<PipelineStatusResponse> {
    return this.http.get<PipelineStatusResponse>(
      `${this.springApiUrl}/${experimentId}/pipeline-status`,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  getPipelineStepLogs(experimentId: number, stepCode: string): Observable<string> {
    return this.http.get(
      `${this.springApiUrl}/${experimentId}/pipeline-steps/${encodeURIComponent(stepCode)}/logs`,
      {
        headers: this.getAuthHeaders(),
        responseType: 'text'
      }
    );
  }

  analyzeModelFile(modelFile: File): Observable<AnalyzeModelFileResponse> {
    const formData = this.buildScriptAnalysisFormData(modelFile);

    /*
     * Important pour AKS :
     * ne jamais appeler http://127.0.0.1:8001 depuis Angular.
     * Le navigateur doit appeler Spring Boot via Nginx : /api/runtime/...
     */
    return this.http.post<AnalyzeModelFileResponse>(
      `${this.runtimeApiUrl}/analyze-script`,
      formData,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  analyzeCustomModelFile(modelFile: File): Observable<AnalyzeModelFileResponse> {
    const formData = this.buildScriptAnalysisFormData(modelFile);

    return this.http.post<AnalyzeModelFileResponse>(
      `${this.runtimeApiUrl}/analyze-script`,
      formData,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  analyzeRuntimeScript(file: File): Observable<RuntimeAnalysisResult> {
    const formData = this.buildScriptAnalysisFormData(file);

    return this.http.post<RuntimeAnalysisResult>(
      `${this.runtimeApiUrl}/analyze-script`,
      formData,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  private buildScriptAnalysisFormData(file: File): FormData {
    const formData = new FormData();

    formData.append('script_file', file);
    formData.append('file', file);
    formData.append('scriptFile', file);
    formData.append('model_file', file);
    formData.append('modelFile', file);

    return formData;
  }

  uploadAndRunCustomModel(
    request: UploadAndRunCustomModelRequest
  ): Observable<MlExperimentResponse> {
    const formData = new FormData();

    formData.append('project_id', String(request.projectId));
    formData.append('projectId', String(request.projectId));

    formData.append('project_name', request.projectName);
    formData.append('projectName', request.projectName);

    formData.append('model_name', request.modelName);
    formData.append('modelName', request.modelName);

    formData.append('run_name', request.runName);
    formData.append('runName', request.runName);

    formData.append('primary_metric', request.primaryMetric);
    formData.append('primaryMetric', request.primaryMetric);

    formData.append('direction', request.direction);

    formData.append('registered_model_name', request.registeredModelName);
    formData.append('registeredModelName', request.registeredModelName);

    const runtimeMode = request.runtimeMode || 'STANDARD_RUNTIME';
    formData.append('runtime_mode', runtimeMode);
    formData.append('runtimeMode', runtimeMode);

    if (request.useOriginalScript !== undefined) {
      formData.append('use_original_script', String(request.useOriginalScript));
      formData.append('useOriginalScript', String(request.useOriginalScript));
    }

    if (request.algorithmInjectionEnabled !== undefined) {
      formData.append('algorithm_injection_enabled', String(request.algorithmInjectionEnabled));
      formData.append('algorithmInjectionEnabled', String(request.algorithmInjectionEnabled));
    }

    if (request.replacementEnabled !== undefined) {
      formData.append('replacement_enabled', String(request.replacementEnabled));
      formData.append('replacementEnabled', String(request.replacementEnabled));
    }

    if (request.customImageUri) {
      formData.append('custom_image_uri', request.customImageUri);
      formData.append('customImageUri', request.customImageUri);
    }

    const replacements = this.normalizeAlgorithmReplacements(
      request.algorithmReplacements || []
    );

    if (replacements.length > 0) {
      const replacementsJson = JSON.stringify(replacements);
      formData.append('algorithm_replacements', replacementsJson);
      formData.append('algorithmReplacements', replacementsJson);
      formData.append('replacements', replacementsJson);
    }

    formData.append('model_file', request.modelFile);
    formData.append('modelFile', request.modelFile);

    if (request.datasetFile) {
      formData.append('dataset_file', request.datasetFile);
      formData.append('datasetFile', request.datasetFile);
    }

    if (request.dockerfileFile) {
      formData.append('dockerfile', request.dockerfileFile);
      formData.append('dockerfileFile', request.dockerfileFile);
    }

    if (request.requirementsFile) {
      formData.append('requirements_file', request.requirementsFile);
      formData.append('requirementsFile', request.requirementsFile);
    }

    return this.http.post<MlExperimentResponse>(
      `${this.springApiUrl}/upload-and-launch`,
      formData,
      {
        headers: this.getAuthHeaders()
      }
    );
  }

  normalizeAlgorithmReplacements(
    replacements: AlgorithmReplacementPayload[]
  ): AlgorithmReplacementPayload[] {
    return (replacements || [])
      .filter((replacement) => {
        return (
          replacement &&
          replacement.original_class &&
          replacement.new_class &&
          replacement.new_lib
        );
      })
      .map((replacement) => {
        const originalClass = this.normalizeAlgorithmClassName(
          replacement.original_class
        );

        const newClass = this.normalizeAlgorithmClassName(replacement.new_class);
        const newLib = replacement.new_lib || this.detectModuleFromAlgorithm(newClass);

        return {
          occurrence_id: replacement.occurrence_id,
          var_name: replacement.var_name || 'model',
          original_class: originalClass,
          new_class: newClass,
          new_lib: newLib,
        };
      });
  }

  private normalizeAlgorithmClassName(value: string): string {
    if (!value) {
      return value;
    }

    const trimmed = value.trim();
    const lower = trimmed.toLowerCase();

    const aliases: Record<string, string> = {
      randomforestclassifier: 'RandomForestClassifier',
      random_forest_classifier: 'RandomForestClassifier',
      'random forest classifier': 'RandomForestClassifier',

      randomforestregressor: 'RandomForestRegressor',
      random_forest_regressor: 'RandomForestRegressor',
      'random forest regressor': 'RandomForestRegressor',

      gradientboostingclassifier: 'GradientBoostingClassifier',
      gradient_boosting_classifier: 'GradientBoostingClassifier',
      'gradient boosting classifier': 'GradientBoostingClassifier',

      gradientboostingregressor: 'GradientBoostingRegressor',
      gradient_boosting_regressor: 'GradientBoostingRegressor',
      'gradient boosting regressor': 'GradientBoostingRegressor',

      extratreesclassifier: 'ExtraTreesClassifier',
      extra_trees_classifier: 'ExtraTreesClassifier',
      'extra trees classifier': 'ExtraTreesClassifier',

      extratreesregressor: 'ExtraTreesRegressor',
      extra_trees_regressor: 'ExtraTreesRegressor',
      'extra trees regressor': 'ExtraTreesRegressor',

      adaboostclassifier: 'AdaBoostClassifier',
      ada_boost_classifier: 'AdaBoostClassifier',
      'adaboost classifier': 'AdaBoostClassifier',

      adaboostregressor: 'AdaBoostRegressor',
      ada_boost_regressor: 'AdaBoostRegressor',
      'adaboost regressor': 'AdaBoostRegressor',

      decisiontreeclassifier: 'DecisionTreeClassifier',
      decision_tree_classifier: 'DecisionTreeClassifier',
      'decision tree classifier': 'DecisionTreeClassifier',

      decisiontreeregressor: 'DecisionTreeRegressor',
      decision_tree_regressor: 'DecisionTreeRegressor',
      'decision tree regressor': 'DecisionTreeRegressor',

      kneighborsclassifier: 'KNeighborsClassifier',
      k_neighbors_classifier: 'KNeighborsClassifier',
      knnclassifier: 'KNeighborsClassifier',
      knn: 'KNeighborsClassifier',

      kneighborsregressor: 'KNeighborsRegressor',
      k_neighbors_regressor: 'KNeighborsRegressor',
      knnregressor: 'KNeighborsRegressor',

      svc: 'SVC',
      svm: 'SVC',
      'svm / svc': 'SVC',
      svr: 'SVR',

      logisticregression: 'LogisticRegression',
      logistic_regression: 'LogisticRegression',
      'logistic regression': 'LogisticRegression',

      linearregression: 'LinearRegression',
      linear_regression: 'LinearRegression',
      'linear regression': 'LinearRegression',

      ridge: 'Ridge',
      lasso: 'Lasso',
      elasticnet: 'ElasticNet',
      elastic_net: 'ElasticNet',

      gaussiannb: 'GaussianNB',
      gaussian_nb: 'GaussianNB',
      naivebayes: 'GaussianNB',
      naive_bayes: 'GaussianNB',

      xgbclassifier: 'XGBClassifier',
      xgboostclassifier: 'XGBClassifier',
      xgboost_classifier: 'XGBClassifier',

      xgbregressor: 'XGBRegressor',
      xgboostregressor: 'XGBRegressor',
      xgboost_regressor: 'XGBRegressor',

      lgbmclassifier: 'LGBMClassifier',
      lightgbmclassifier: 'LGBMClassifier',
      lightgbm_classifier: 'LGBMClassifier',

      lgbmregressor: 'LGBMRegressor',
      lightgbmregressor: 'LGBMRegressor',
      lightgbm_regressor: 'LGBMRegressor',
    };

    const key = lower.replace(/[\s.-]+/g, '_');
    const compactKey = lower.replace(/[^a-z0-9]/g, '');

    return aliases[lower] || aliases[key] || aliases[compactKey] || trimmed;
  }

  private detectModuleFromAlgorithm(className: string): string {
    const normalized = this.normalizeAlgorithmClassName(className);

    if (
      [
        'RandomForestClassifier',
        'RandomForestRegressor',
        'GradientBoostingClassifier',
        'GradientBoostingRegressor',
        'ExtraTreesClassifier',
        'ExtraTreesRegressor',
        'AdaBoostClassifier',
        'AdaBoostRegressor',
      ].includes(normalized)
    ) {
      return 'sklearn.ensemble';
    }

    if (['DecisionTreeClassifier', 'DecisionTreeRegressor'].includes(normalized)) {
      return 'sklearn.tree';
    }

    if (['KNeighborsClassifier', 'KNeighborsRegressor'].includes(normalized)) {
      return 'sklearn.neighbors';
    }

    if (['SVC', 'SVR'].includes(normalized)) {
      return 'sklearn.svm';
    }

    if (
      [
        'LogisticRegression',
        'LinearRegression',
        'Ridge',
        'Lasso',
        'ElasticNet'
      ].includes(normalized)
    ) {
      return 'sklearn.linear_model';
    }

    if (normalized === 'GaussianNB') {
      return 'sklearn.naive_bayes';
    }

    if (['XGBClassifier', 'XGBRegressor'].includes(normalized)) {
      return 'xgboost';
    }

    if (['LGBMClassifier', 'LGBMRegressor'].includes(normalized)) {
      return 'lightgbm';
    }

    return 'sklearn.ensemble';
  }
}
