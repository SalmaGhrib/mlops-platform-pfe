import { CommonModule } from '@angular/common';
import { Component, OnInit, ViewChild } from '@angular/core';
import { Observable, catchError, finalize, forkJoin, map, of } from 'rxjs';
import {
  ModelApiKeyItem,
  ModelApiKeyStats,
  ModelDeploymentResponse,
  ModelRegistryItem,
  ModelRegistryService
} from '../../core/services/model-registry.service';
import { SmartSearchComponent } from '../../shared/components/common/smart-search/smart-search.component';
import {
  SearchPageResponse,
  SmartSearchParams
} from '../../shared/components/common/smart-search/smart-search.types';

@Component({
  selector: 'app-model-registry',
  standalone: true,
  imports: [CommonModule, SmartSearchComponent],
  templateUrl: './model-registry.component.html',
  styleUrls: ['./model-registry.component.css']
})
export class ModelRegistryComponent implements OnInit {
  @ViewChild(SmartSearchComponent) smartSearch!: SmartSearchComponent<ModelRegistryItem>;

  models: ModelRegistryItem[] = [];
  totalModels = 0;

  loading = false;
  syncing = false;
  deploying = false;

  errorMessage = '';
  successMessage = '';

  constructor(private modelRegistryService: ModelRegistryService) {}

  ngOnInit(): void {
    // Le composant app-smart-search charge automatiquement la première page.
  }

  loadModels(): void {
    this.smartSearch?.resetAndReload();
  }

  searchModels = (params: SmartSearchParams): Observable<SearchPageResponse<ModelRegistryItem>> =>
    this.modelRegistryService.searchMyModels(params);

  onSearchItems(models: ModelRegistryItem[]): void {
    this.models = models || [];
    this.enrichModelsWithActiveDeployments();
  }

  onTotalChange(total: number): void {
    this.totalModels = total;
  }

  refresh(): void {
    this.smartSearch?.resetAndReload();
  }

  syncModels(): void {
    this.syncing = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.modelRegistryService.syncMyModels()
      .pipe(
        catchError((error) => {
          console.error('[ModelRegistry] Failed to sync models', error);
          this.errorMessage = this.extractErrorMessage(
            error,
            'Impossible de synchroniser le Model Registry.'
          );
          return of([]);
        }),
        map((models) => models || []),
        finalize(() => {
          this.syncing = false;
        })
      )
      .subscribe((models) => {
        this.successMessage = 'Model Registry synchronisé avec succès.';
        this.clearSuccessAfterDelay();
        this.smartSearch?.reload();
      });
  }

  private enrichModelsWithActiveDeployments(): void {
    if (!this.models || this.models.length === 0) {
      return;
    }

    const requests = this.models.map((model) => {
      if (!model?.projectId) {
        return of(null);
      }

      return this.modelRegistryService.getActiveDeploymentByProject(model.projectId)
        .pipe(
          catchError(() => of(null))
        );
    });

    forkJoin(requests).subscribe((deployments) => {
      this.models = this.models.map((model, index) => {
        const deployment = deployments[index];

        if (!deployment) {
          return model;
        }

        return this.mergeModelWithDeployment(model, deployment);
      });
    });
  }

  private mergeModelWithDeployment(
    model: ModelRegistryItem,
    deployment: ModelDeploymentResponse
  ): ModelRegistryItem {
    return {
      ...model,

      deploymentStatus: deployment.deploymentStatus ?? model.deploymentStatus,
      predictionEndpoint: deployment.predictionEndpoint ?? model.predictionEndpoint,
      dockerImage: deployment.dockerImage ?? model.dockerImage,
      errorMessage: deployment.errorMessage ?? model.errorMessage,
      activeDeploymentId: deployment.id ?? model.activeDeploymentId,

      apiEnabled: deployment.apiEnabled ?? model.apiEnabled,
      visibility: deployment.visibility ?? model.visibility,
      apiKey: deployment.apiKey ?? model.apiKey,

      deployedAt: deployment.deployedAt,
      deploymentName: deployment.deploymentName,
      serviceName: deployment.serviceName,
      namespaceName: deployment.namespaceName,

      modelVersion: deployment.modelVersion ?? model['modelVersion'],
      productionVersion:
        model.productionVersion ||
        deployment.mlflowProductionVersion ||
        deployment.productionVersion ||
        deployment.modelVersion
    };
  }

  trackModel(index: number, model: ModelRegistryItem): string {
    return `${model?.projectId || 'project'}-${model?.registeredModelName || index}`;
  }

  /*
   * =========================================================
   * MODEL CARD
   * =========================================================
   */

  modelCardModel: ModelRegistryItem | null = null;

  openModelCard(model: ModelRegistryItem): void {
    this.modelCardModel = model;
    document.body.style.overflow = 'hidden';
  }

  closeModelCard(): void {
    this.modelCardModel = null;
    document.body.style.overflow = '';
  }

  getModelCardDescription(model: ModelRegistryItem): string {
    return (
      model?.['description'] ||
      model?.['modelDescription'] ||
      model?.['registeredModelDescription'] ||
      model?.['projectDescription'] ||
      ''
    );
  }

  hasAlgorithmInfo(model: ModelRegistryItem): boolean {
    return (
      !!model?.usedAlgorithms ||
      !!model?.detectedAlgorithms ||
      this.hasReplacement(model)
    );
  }

  getAlgorithmsList(model: ModelRegistryItem): string[] {
    const used = model?.usedAlgorithms;
    const detected = model?.detectedAlgorithms;

    const values = [
      ...this.normalizeAlgorithmList(used),
      ...this.normalizeAlgorithmList(detected)
    ];

    return [...new Set(values)];
  }

  private normalizeAlgorithmList(value: string | any[] | null | undefined): string[] {
    if (!value) {
      return [];
    }

    if (Array.isArray(value)) {
      return value
        .map((item) => {
          if (typeof item === 'string') {
            return item;
          }
          if (item?.name) {
            return item.name;
          }
          if (item?.algorithm) {
            return item.algorithm;
          }
          return String(item);
        })
        .filter((item) => item && String(item).trim() !== '');
    }

    return String(value).split(',').map((item) => item.trim()).filter(Boolean);
  }

  getModelCardHealthLevel(model: ModelRegistryItem): 'success' | 'warning' | 'error' {
    if (this.isFailed(model)) {
      return 'error';
    }

    if (this.isDeploymentFailed(model)) {
      return 'error';
    }

    if (model?.hasProduction && this.isDeploymentDeployed(model)) {
      return 'success';
    }

    if (model?.hasProduction) {
      return 'warning';
    }

    return 'warning';
  }

  isDeploymentDeployed(model: ModelRegistryItem): boolean {
    const status =
      model?.deploymentStatus ||
      model?.['deployment_status'] ||
      model?.['status'];

    return String(status || '').toUpperCase() === 'DEPLOYED';
  }

  isDeploymentFailed(model: ModelRegistryItem): boolean {
    const status =
      model?.deploymentStatus ||
      model?.['deployment_status'] ||
      model?.['status'];

    return String(status || '').toUpperCase() === 'FAILED';
  }

  isDeploymentDeploying(model: ModelRegistryItem): boolean {
    const status =
      model?.deploymentStatus ||
      model?.['deployment_status'] ||
      model?.['status'];

    const clean = String(status || '').toUpperCase();

    return clean === 'DEPLOYING' || clean === 'PENDING' || clean === 'BUILDING_IMAGE';
  }

  getProductionBadgeClass(model: ModelRegistryItem): string {
    if (model?.hasProduction) {
      return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300';
    }

    return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300';
  }

  getDeploymentBadgeClass(model: ModelRegistryItem): string {
    if (this.isDeploymentDeployed(model)) {
      return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300';
    }

    if (this.isDeploymentFailed(model)) {
      return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300';
    }

    if (this.isDeploymentDeploying(model)) {
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300';
    }

    return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300';
  }

  getDeploymentLabel(model: ModelRegistryItem): string {
    const status =
      model?.deploymentStatus ||
      model?.['deployment_status'];

    if (!status) {
      return model?.hasProduction ? 'AUTO DEPLOY EN ATTENTE' : 'NOT DEPLOYED';
    }

    const clean = String(status).toUpperCase();

    if (clean === 'DEPLOYED') {
      return 'DEPLOYED';
    }

    if (clean === 'FAILED') {
      return 'FAILED';
    }

    if (clean === 'DEPLOYING') {
      return 'DEPLOYING';
    }

    if (clean === 'PENDING') {
      return 'PENDING';
    }

    return clean;
  }

  formatDate(value?: string | null): string {
    if (!value) {
      return '-';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString();
  }

  formatGlobalScore(value?: number | null): string {
    if (value === null || value === undefined) {
      return '-';
    }

    const numeric = Number(value);

    if (Number.isNaN(numeric)) {
      return '-';
    }

    return numeric.toFixed(4);
  }

  formatMetric(metric?: string | null): string {
    if (!metric) {
      return '-';
    }

    return String(metric)
      .replaceAll('_', ' ')
      .replace(/\b\w/g, (char) => char.toUpperCase());
  }

  formatMetricValue(value?: number | string | null): string {
    if (value === null || value === undefined || value === '') {
      return '-';
    }

    const numeric = Number(value);

    if (Number.isNaN(numeric)) {
      return String(value);
    }

    return numeric.toFixed(4);
  }

  hasMetrics(model: ModelRegistryItem): boolean {
    const metrics = this.getMetrics(model);
    return !!metrics && Object.keys(metrics).length > 0;
  }

  isFailed(model: ModelRegistryItem): boolean {
    const normalized = String(model?.status || '').toUpperCase();
    return normalized === 'FAILED' || normalized === 'ERROR' || normalized === 'BUILD_FAILED';
  }

  getMetrics(model: ModelRegistryItem): Record<string, number> {
    return (
      model?.productionMetrics ||
      model?.metrics ||
      {}
    );
  }

  getMainUsedAlgorithm(model: ModelRegistryItem): string {
    const used = model?.usedAlgorithms;
    const detected = model?.detectedAlgorithms;

    const value = this.normalizeAlgorithmValue(used) || this.normalizeAlgorithmValue(detected);

    return value || 'Non détecté';
  }

  private normalizeAlgorithmValue(value: string | any[] | null | undefined): string {
    if (!value) {
      return '';
    }

    if (Array.isArray(value)) {
      return value
        .map((item) => {
          if (typeof item === 'string') {
            return item;
          }

          if (item?.name) {
            return item.name;
          }

          if (item?.algorithm) {
            return item.algorithm;
          }

          return String(item);
        })
        .filter(Boolean)
        .join(', ');
    }

    return String(value);
  }

  hasReplacement(model: ModelRegistryItem): boolean {
    const raw = model?.hasAlgorithmReplacement;

    if (raw === true) {
      return true;
    }

    if (raw === false || raw === null || raw === undefined) {
      return Number(model?.algorithmReplacementCount || 0) > 0;
    }

    const clean = String(raw).toLowerCase();

    return clean === 'true' || clean === '1' || Number(model?.algorithmReplacementCount || 0) > 0;
  }

  getReplacementCount(model: ModelRegistryItem): number {
    const raw = model?.algorithmReplacementCount;

    if (raw === null || raw === undefined || raw === '') {
      return 0;
    }

    const numeric = Number(raw);

    return Number.isNaN(numeric) ? 0 : numeric;
  }

  getExternalApiEndpoint(model: ModelRegistryItem): string {
    if (!model || !model.registeredModelName) {
      return '';
    }

    return this.modelRegistryService.getExternalPredictionEndpoint(model.registeredModelName);
  }

  getApiKey(model: ModelRegistryItem): string {
    if (!model) {
      return '';
    }

    return (
      model.apiKey ||
      model['api_key'] ||
      model['apiKeyValue'] ||
      model['api_key_value'] ||
      ''
    );
  }

  isApiEnabled(model: ModelRegistryItem): boolean {
    if (!model) {
      return false;
    }

    return (
      model.apiEnabled === true ||
      model['api_enabled'] === true ||
      String(model.apiEnabled).toLowerCase() === 'true' ||
      String(model['api_enabled']).toLowerCase() === 'true'
    );
  }

  maskApiKey(apiKey: string): string {
    if (!apiKey) {
      return 'Clé API non disponible';
    }

    if (apiKey.length <= 16) {
      return apiKey;
    }

    return `${apiKey.substring(0, 12)}********${apiKey.substring(apiKey.length - 6)}`;
  }

  /*
   * =========================================================
   * SMART MODEL GATEWAY
   * =========================================================
   */

  gatewayOpen: { [deploymentId: number]: boolean } = {};
  gatewayKeys: { [deploymentId: number]: ModelApiKeyItem[] } = {};
  gatewayStats: { [deploymentId: number]: ModelApiKeyStats } = {};
  gatewayLoading: { [deploymentId: number]: boolean } = {};
  gatewayNewName: { [deploymentId: number]: string } = {};
  gatewayNewQuota: { [deploymentId: number]: number | null } = {};
  gatewayNewKey: { [deploymentId: number]: string | null } = {};
  gatewayNewExpiry: { [deploymentId: number]: string } = {};
  gatewayKeyModalDeploymentId: number | null = null;

  isGatewayOpen(deploymentId?: number | null): boolean {
    return deploymentId != null && this.gatewayOpen[deploymentId] === true;
  }

  isGatewayLoading(deploymentId?: number | null): boolean {
    return deploymentId != null && this.gatewayLoading[deploymentId] === true;
  }

  toggleGateway(model: ModelRegistryItem): void {
    const deploymentId = model?.activeDeploymentId;

    if (!deploymentId) {
      return;
    }

    if (this.gatewayOpen[deploymentId]) {
      this.gatewayOpen[deploymentId] = false;
      return;
    }

    this.gatewayOpen[deploymentId] = true;
    this.loadGateway(model);
  }

  loadGateway(model: ModelRegistryItem): void {
    const deploymentId = model?.activeDeploymentId;

    if (!deploymentId) {
      return;
    }

    this.gatewayLoading[deploymentId] = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.modelRegistryService.getApiKeys(deploymentId).subscribe({
      next: (keys) => {
        this.gatewayKeys[deploymentId] = keys || [];
        this.gatewayLoading[deploymentId] = false;
      },
      error: (error) => {
        this.gatewayKeys[deploymentId] = [];
        this.gatewayLoading[deploymentId] = false;
        this.errorMessage = this.extractErrorMessage(
          error,
          'Impossible de charger les clés API.'
        );
      }
    });

    this.modelRegistryService.getApiKeyStats(deploymentId).subscribe({
      next: (stats) => {
        this.gatewayStats[deploymentId] = stats;
      },
      error: () => {
        this.gatewayStats[deploymentId] = {};
      }
    });
  }

  getGatewayKeys(model: ModelRegistryItem): ModelApiKeyItem[] {
    const deploymentId = model?.activeDeploymentId;
    return deploymentId != null ? (this.gatewayKeys[deploymentId] || []) : [];
  }

  getGatewayStats(model: ModelRegistryItem): ModelApiKeyStats | null {
    const deploymentId = model?.activeDeploymentId;
    return deploymentId != null ? (this.gatewayStats[deploymentId] || null) : null;
  }

  getGatewayKeysCount(model: ModelRegistryItem): number {
    return this.getGatewayKeys(model).length;
  }

  getLastUsedLabel(model: ModelRegistryItem): string {
    const keys = this.getGatewayKeys(model);

    if (!keys || keys.length === 0) {
      return 'Aucune utilisation';
    }

    let latest: string | null = null;

    for (const key of keys) {
      const used = key?.lastUsedAt;
      if (used && (!latest || new Date(used).getTime() > new Date(latest).getTime())) {
        latest = used;
      }
    }

    return latest ? this.formatDate(latest) : 'Aucune utilisation';
  }

  getProductionVersion(model: ModelRegistryItem): string {
    const version =
      model?.productionVersion ||
      model?.['mlflowProductionVersion'] ||
      model?.['mlflow_production_version'] ||
      model?.['modelVersion'] ||
      model?.['model_version'];

    return version != null && String(version).trim() !== '' ? String(version) : '—';
  }

  getModelVersion(model: ModelRegistryItem): string {
    const version =
      model?.latestVersion ||
      model?.['modelVersion'] ||
      model?.['model_version'] ||
      model?.['mlflowVersion'] ||
      model?.['mlflow_version'];

    return version != null && String(version).trim() !== '' ? String(version) : '—';
  }

  getGatewayNewName(deploymentId?: number | null): string {
    return deploymentId != null ? (this.gatewayNewName[deploymentId] || '') : '';
  }

  getGatewayNewQuota(deploymentId?: number | null): number | null {
    return deploymentId != null ? (this.gatewayNewQuota[deploymentId] ?? null) : null;
  }

  getGatewayNewExpiry(deploymentId?: number | null): string {
    return deploymentId != null ? (this.gatewayNewExpiry[deploymentId] || '') : '';
  }

  isModelPrivate(model: ModelRegistryItem): boolean {
    if (!model) {
      return true;
    }

    const visibility = model.visibility || model['visibilityValue'] || '';

    if (String(visibility).toUpperCase() === 'PRIVATE') {
      return true;
    }

    return !this.isApiEnabled(model) && !model.visibility;
  }

  parseGatewayQuota(value: any): number | null {
    if (value === null || value === undefined) {
      return null;
    }

    const raw = String(value).trim();

    if (raw === '') {
      return null;
    }

    const parsed = Number(raw);

    return !isNaN(parsed) && parsed > 0 ? parsed : null;
  }

  formatGatewayExpiry(value: string): string | null {
    const raw = String(value || '').trim();

    if (!raw) {
      return null;
    }

    const dateOnly = raw.match(/^(\d{4})-(\d{2})-(\d{2})$/);

    if (dateOnly) {
      return `${dateOnly[1]}-${dateOnly[2]}-${dateOnly[3]}T00:00:00`;
    }

    const expiry = new Date(raw);

    if (isNaN(expiry.getTime())) {
      return null;
    }

    const pad = (n: number) => String(n).padStart(2, '0');

    return `${expiry.getFullYear()}-${pad(expiry.getMonth() + 1)}-${pad(expiry.getDate())}T00:00:00`;
  }

  createGatewayKey(model: ModelRegistryItem): void {
    const deploymentId = model?.activeDeploymentId;

    if (!deploymentId) {
      this.errorMessage = 'Aucun déploiement actif pour créer une clé API.';
      return;
    }

    if (this.isModelPrivate(model)) {
      this.errorMessage = 'Ce modèle est privé : la création de clé API est désactivée.';
      return;
    }

    const name = this.gatewayNewName[deploymentId] || '';
    const quota = this.gatewayNewQuota[deploymentId];
    const expiryValue = this.gatewayNewExpiry[deploymentId] || '';

    const body: {
      name?: string | null;
      dailyQuota?: number | null;
      expiresAt?: string | null;
    } = {};

    if (name.trim()) {
      body.name = name.trim();
    }

    if (quota != null && quota > 0) {
      body.dailyQuota = quota;
    }

    if (expiryValue) {
      const formatted = this.formatGatewayExpiry(expiryValue);
      if (formatted) {
        body.expiresAt = formatted;
      }
    }

    this.errorMessage = '';
    this.successMessage = '';
    this.gatewayLoading[deploymentId] = true;

    this.modelRegistryService.createApiKey(deploymentId, body).subscribe({
      next: (key) => {
        this.gatewayLoading[deploymentId] = false;

        if (key?.apiKey) {
          this.gatewayNewKey[deploymentId] = key.apiKey;
          this.gatewayKeyModalDeploymentId = deploymentId;
        } else {
          this.successMessage = 'Clé API créée avec succès.';
          this.clearSuccessAfterDelay();
        }

        const keys = this.gatewayKeys[deploymentId] || [];
        this.gatewayKeys[deploymentId] = [key, ...keys];

        this.gatewayNewName[deploymentId] = '';
        this.gatewayNewQuota[deploymentId] = null;
        this.gatewayNewExpiry[deploymentId] = '';

        this.clearSuccessAfterDelay();
      },
      error: (error) => {
        this.gatewayLoading[deploymentId] = false;
        this.errorMessage = this.extractErrorMessage(
          error,
          'Impossible de créer la clé API.'
        );
      }
    });
  }

  getGatewayNewKey(model: ModelRegistryItem): string | null {
    const deploymentId = model?.activeDeploymentId;
    return deploymentId != null ? (this.gatewayNewKey[deploymentId] || null) : null;
  }

  isKeyModalOpen(deploymentId?: number | null): boolean {
    return deploymentId != null && this.gatewayKeyModalDeploymentId === deploymentId;
  }

  /*
   * Ferme la fenêtre de révélation : la clé complète n'est plus jamais affichée ensuite.
   * Seul le préfixe masqué reste visible dans la liste.
   */
  closeGatewayKeyModal(model: ModelRegistryItem): void {
    const deploymentId = model?.activeDeploymentId;

    if (deploymentId != null) {
      this.gatewayKeyModalDeploymentId = null;
      this.gatewayNewKey[deploymentId] = null;
      this.successMessage =
        'Clé API créée. Copiez-la maintenant : elle ne sera plus affichée après la fermeture de cette fenêtre.';
      this.clearSuccessAfterDelay();
    }
  }

  copyGatewayKey(model: ModelRegistryItem): void {
    const deploymentId = model?.activeDeploymentId;
    const key = deploymentId != null ? (this.gatewayNewKey[deploymentId] || null) : null;

    if (!key) {
      return;
    }

    this.copyToClipboard(key, 'Clé API copiée.');
  }

  copyPublicEndpoint(model: ModelRegistryItem): void {
    if (!model?.registeredModelName) {
      return;
    }

    const url = this.modelRegistryService.getExternalPredictionEndpoint(
      model.registeredModelName
    );

    const fullUrl = `${this.modelRegistryService.getPublicBaseUrlForCopy() || this.getDefaultBaseUrl()}${url}`;

    this.copyToClipboard(fullUrl, 'Endpoint public copié.');
  }

  copyCurl(model: ModelRegistryItem): void {
    if (!model?.registeredModelName) {
      return;
    }

    const curl = this.modelRegistryService.buildCurlForModel(
      model.registeredModelName,
      null
    );

    this.copyToClipboard(curl, 'Commande cURL copiée.');
  }

  maskGatewayKey(key: ModelApiKeyItem): string {
    if (key?.maskedKey) {
      return key.maskedKey;
    }

    if (key?.keyPrefix) {
      return `${key.keyPrefix}••••••`;
    }

    return 'clé-api-••••••';
  }

  isKeyEnabled(key: ModelApiKeyItem): boolean {
    return key?.enabled === true && !key?.revokedAt;
  }

  isKeyExpired(key: ModelApiKeyItem): boolean {
    if (!key?.expiresAt) {
      return false;
    }

    const expiry = new Date(key.expiresAt);
    return !isNaN(expiry.getTime()) && expiry.getTime() < Date.now();
  }

  toggleKeyEnabled(key: ModelApiKeyItem, model: ModelRegistryItem): void {
    const deploymentId = model?.activeDeploymentId;

    if (!deploymentId || !key?.id) {
      return;
    }

    const currentlyEnabled = this.isKeyEnabled(key);

    const observable = currentlyEnabled
      ? this.modelRegistryService.disableApiKey(key.id)
      : this.modelRegistryService.enableApiKey(key.id);

    observable.subscribe({
      next: (updated) => {
        const keys = this.gatewayKeys[deploymentId] || [];
        this.gatewayKeys[deploymentId] = keys.map((item) =>
          item.id === updated.id ? updated : item
        );
      },
      error: (error) => {
        this.errorMessage = this.extractErrorMessage(
          error,
          'Impossible de modifier la clé API.'
        );
      }
    });
  }

  revokeKey(key: ModelApiKeyItem, model: ModelRegistryItem): void {
    const deploymentId = model?.activeDeploymentId;

    if (!deploymentId || !key?.id) {
      return;
    }

    const confirmed = confirm(
      'Révoquer cette clé API ? Elle deviendra immédiatement inutilisable.'
    );

    if (!confirmed) {
      return;
    }

    this.modelRegistryService.revokeApiKey(key.id).subscribe({
      next: () => {
        const keys = this.gatewayKeys[deploymentId] || [];
        this.gatewayKeys[deploymentId] = keys.map((item) => {
          if (item.id !== key.id) {
            return item;
          }
          return {
            ...item,
            enabled: false,
            revokedAt: new Date().toISOString()
          };
        });
      },
      error: (error) => {
        this.errorMessage = this.extractErrorMessage(
          error,
          'Impossible de révoquer la clé API.'
        );
      }
    });
  }

  formatQuota(quota?: number | null): string {
    if (quota == null) {
      return 'Illimité';
    }
    return `${quota} req/jour`;
  }

  formatKeyStatus(key: ModelApiKeyItem): string {
    if (this.isKeyExpired(key)) {
      return 'Expirée';
    }
    if (key?.revokedAt) {
      return 'Révoquée';
    }
    if (this.isKeyEnabled(key)) {
      return 'Active';
    }
    return 'Désactivée';
  }

  formatLastUsed(lastUsedAt?: string | null): string {
    if (!lastUsedAt) {
      return 'Jamais utilisée';
    }
    return this.formatDate(lastUsedAt);
  }

  private getDefaultBaseUrl(): string {
    return 'http://128.251.112.197';
  }

  getPublicBaseUrlForDisplay(registeredModelName: string): string {
    const cleanName = encodeURIComponent(registeredModelName || '');
    const base = this.modelRegistryService.getPublicBaseUrlForCopy() || this.getDefaultBaseUrl();
    return `${base}/api/external/models/${cleanName}/predict`;
  }

  getModelDataType(model: ModelRegistryItem): string {
    const raw =
      model?.['dataType'] ||
      model?.['data_type'] ||
      model?.['projectDataType'] ||
      model?.['project_data_type'] ||
      'TABULAR';

    return String(raw).trim().toUpperCase();
  }

  getExampleJson(model: ModelRegistryItem): string {
    const dataType = this.getModelDataType(model);

    if (dataType === 'TEXT' || dataType === 'NLP') {
      return JSON.stringify(
        {
          text: 'le service est excellent et rapide'
        },
        null,
        2
      );
    }

    if (dataType === 'IMAGE') {
      return JSON.stringify(
        {
          imageBase64: 'COLLER_IMAGE_BASE64_ICI'
        },
        null,
        2
      );
    }

    return JSON.stringify(
      {
        feature_1: 1.2,
        feature_2: 0.4,
        feature_3: 2.1,
        feature_4: 1.0,
        feature_5: 0.7,
        feature_6: 3.2
      },
      null,
      2
    );
  }

  getRowsExampleJson(model: ModelRegistryItem): string {
    const dataType = this.getModelDataType(model);

    if (dataType === 'TEXT' || dataType === 'NLP' || dataType === 'IMAGE') {
      return this.getExampleJson(model);
    }

    return JSON.stringify(
      {
        rows: [
          {
            feature_1: 1.2,
            feature_2: 0.4,
            feature_3: 2.1,
            feature_4: 1.0,
            feature_5: 0.7,
            feature_6: 3.2
          }
        ]
      },
      null,
      2
    );
  }

  getPowerShellExample(model: ModelRegistryItem): string {
    const endpoint = this.getExternalApiEndpoint(model);
    const apiKey = this.getApiKey(model) || 'VOTRE_CLE_API';
    const body = this.getExampleJson(model);

    return `Invoke-RestMethod \`
  -Uri "${endpoint}" \`
  -Method POST \`
  -ContentType "application/json" \`
  -Headers @{ "X-API-KEY" = "${apiKey}" } \`
  -Body '${body}'`;
  }

  copyToClipboard(value: string, message = 'Copié.'): void {
    if (!value) {
      this.errorMessage = 'Rien à copier.';
      return;
    }

    if (navigator?.clipboard?.writeText) {
      navigator.clipboard.writeText(value)
        .then(() => {
          this.successMessage = message;
          this.clearSuccessAfterDelay();
        })
        .catch(() => {
          this.fallbackCopyToClipboard(value, message);
        });

      return;
    }

    this.fallbackCopyToClipboard(value, message);
  }

  private fallbackCopyToClipboard(value: string, message: string): void {
    const textarea = document.createElement('textarea');
    textarea.value = value;
    textarea.style.position = 'fixed';
    textarea.style.left = '-9999px';
    textarea.style.top = '-9999px';

    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();

    try {
      document.execCommand('copy');
      this.successMessage = message;
      this.clearSuccessAfterDelay();
    } catch {
      this.errorMessage = 'Impossible de copier automatiquement.';
    } finally {
      document.body.removeChild(textarea);
    }
  }

  private clearSuccessAfterDelay(): void {
    setTimeout(() => {
      this.successMessage = '';
    }, 2500);
  }

  enableApiAccess(model: ModelRegistryItem): void {
  const deploymentId = model?.activeDeploymentId;

  if (!deploymentId) {
    this.errorMessage = 'Aucun déploiement actif trouvé pour activer l’API.';
    return;
  }

  this.errorMessage = '';
  this.successMessage = '';

  this.modelRegistryService.enableApiAccess(deploymentId).subscribe({
    next: (deployment) => {
      this.successMessage = 'API publique activée avec succès.';
      this.clearSuccessAfterDelay();

      this.models = this.models.map((item) => {
        if (item.projectId !== model.projectId) {
          return item;
        }

        return this.mergeModelWithDeployment(item, deployment);
      });
    },
    error: (error) => {
      this.errorMessage = this.extractErrorMessage(
        error,
        'Impossible d’activer l’API publique.'
      );
    }
  });
}

disableApiAccess(model: ModelRegistryItem): void {
  const deploymentId = model?.activeDeploymentId;

  if (!deploymentId) {
    this.errorMessage = 'Aucun déploiement actif trouvé pour désactiver l’API.';
    return;
  }

  this.errorMessage = '';
  this.successMessage = '';

  this.modelRegistryService.disableApiAccess(deploymentId).subscribe({
    next: (deployment) => {
      this.successMessage = 'API désactivée avec succès.';
      this.clearSuccessAfterDelay();

      this.models = this.models.map((item) => {
        if (item.projectId !== model.projectId) {
          return item;
        }

        return this.mergeModelWithDeployment(item, deployment);
      });
    },
    error: (error) => {
      this.errorMessage = this.extractErrorMessage(
        error,
        'Impossible de désactiver l’API.'
      );
    }
  });
}

regenerateApiKey(model: ModelRegistryItem): void {
  const deploymentId = model?.activeDeploymentId;

  if (!deploymentId) {
    this.errorMessage = 'Aucun déploiement actif trouvé pour régénérer la clé API.';
    return;
  }

  const confirmed = confirm(
    'Voulez-vous vraiment régénérer la clé API ? L’ancienne clé ne fonctionnera plus.'
  );

  if (!confirmed) {
    return;
  }

  this.errorMessage = '';
  this.successMessage = '';

  this.modelRegistryService.regenerateApiKey(deploymentId).subscribe({
    next: (deployment) => {
      this.successMessage = 'Nouvelle clé API générée avec succès.';
      this.clearSuccessAfterDelay();

      this.models = this.models.map((item) => {
        if (item.projectId !== model.projectId) {
          return item;
        }

        return this.mergeModelWithDeployment(item, deployment);
      });
    },
    error: (error) => {
      this.errorMessage = this.extractErrorMessage(
        error,
        'Impossible de régénérer la clé API.'
      );
    }
  });
}

  private extractErrorMessage(error: any, fallback: string): string {
    return (
      error?.error?.message ||
      error?.error?.error ||
      error?.message ||
      fallback
    );
  }
}