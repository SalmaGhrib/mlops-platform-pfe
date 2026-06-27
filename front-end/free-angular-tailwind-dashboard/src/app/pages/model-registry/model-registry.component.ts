import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { catchError, finalize, forkJoin, map, of } from 'rxjs';
import {
  ModelDeploymentResponse,
  ModelRegistryItem,
  ModelRegistryService
} from '../../core/services/model-registry.service';

@Component({
  selector: 'app-model-registry',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './model-registry.component.html',
  styleUrls: ['./model-registry.component.css']
})
export class ModelRegistryComponent implements OnInit {
  models: ModelRegistryItem[] = [];

  loading = false;
  syncing = false;
  deploying = false;

  errorMessage = '';
  successMessage = '';

  constructor(private modelRegistryService: ModelRegistryService) {}

  ngOnInit(): void {
    this.loadModels();
  }

  loadModels(): void {
    this.loading = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.modelRegistryService.getMyModels()
      .pipe(
        catchError((error) => {
          console.error('[ModelRegistry] Failed to load models', error);
          this.errorMessage = this.extractErrorMessage(
            error,
            'Impossible de charger le Model Registry.'
          );
          return of([]);
        }),
        map((models) => models || []),
        finalize(() => {
          this.loading = false;
        })
      )
      .subscribe((models) => {
        this.models = models;
        this.enrichModelsWithActiveDeployments();
      });
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
        this.models = models;
        this.successMessage = 'Model Registry synchronisé avec succès.';
        this.clearSuccessAfterDelay();
        this.enrichModelsWithActiveDeployments();
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
      productionVersion: model.productionVersion || deployment.modelVersion
    };
  }

  trackModel(index: number, model: ModelRegistryItem): string {
    return `${model?.projectId || 'project'}-${model?.registeredModelName || index}`;
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