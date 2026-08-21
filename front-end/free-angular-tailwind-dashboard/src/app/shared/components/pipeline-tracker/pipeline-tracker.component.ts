import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  ExperimentService,
  MlExperimentResponse,
  PipelineStatusResponse,
  RunPipelineStepDto
} from '../../../core/services/experiment.service';
import { ModelDeploymentResponse } from '../../../core/services/model-registry.service';

export interface PipelineStep {
  key: string;
  label: string;
  hint: string;
  state: 'done' | 'active' | 'pending' | 'failed' | 'skipped';
  duration?: string;
  errorCode?: string;
  jobName?: string;
  hasLogs?: boolean;
}

const STANDARD_PIPELINE: Record<string, { label: string; hint: string }> = {
  SUBMITTED: { label: 'Soumission', hint: 'Fichiers et configuration validés' },
  KFP_PIPELINE_CREATED: { label: 'Pipeline Kubeflow', hint: 'Composant KFP créé' },
  KFP_RUN_SUBMITTED: { label: 'Run Kubeflow', hint: 'Run soumis dans Kubeflow' },
  DATA_PREPARED: { label: 'Données', hint: 'Dataset préparé pour l’entraînement' },
  KFP_TRAINING_RUNNING: { label: 'Entraînement', hint: 'Pipeline Kubeflow en exécution' },
  METRICS_EXTRACTED: { label: 'Métriques', hint: 'Métriques extraites des logs' },
  MODEL_ARTIFACT_DETECTED: { label: 'Artifact modèle', hint: 'Fichier modèle détecté' },
  MLFLOW_REGISTERED: { label: 'Registry MLflow', hint: 'Modèle enregistré dans MLflow' },
  PRODUCTION_PROMOTED: { label: 'Production', hint: 'Version promue en production' },
  DEPLOYMENT_READY: { label: 'Déploiement', hint: 'Modèle prêt pour le déploiement' }
};

const CUSTOM_PIPELINE: Record<string, { label: string; hint: string }> = {
  FILES_VALIDATED: { label: 'Validation', hint: 'Script, Dockerfile et requirements validés' },
  IMAGE_BUILD_STARTED: { label: 'Build image (Kaniko)', hint: 'Job Kaniko de construction de l’image' },
  IMAGE_BUILD_COMPLETED: { label: 'Image construite', hint: 'Image Docker construite' },
  ACR_PUSH_COMPLETED: { label: 'Push ACR', hint: 'Image poussée dans le registry ACR' },
  TRAINING_JOB_CREATED: { label: 'Job Kubernetes', hint: 'Job d’entraînement créé' },
  TRAINING_RUNNING: { label: 'Entraînement', hint: 'Pod d’entraînement exécuté' },
  USER_SCRIPT_COMPLETED: { label: 'Script utilisateur', hint: 'train.py exécuté avec succès' },
  METRICS_EXTRACTED: { label: 'Métriques', hint: 'Métriques extraites des logs' },
  MODEL_ARTIFACT_DETECTED: { label: 'Artifact modèle', hint: 'Fichier modèle détecté' },
  MLFLOW_REGISTERED: { label: 'Registry MLflow', hint: 'Modèle enregistré dans MLflow' },
  PRODUCTION_PROMOTED: { label: 'Production', hint: 'Version promue en production' },
  DEPLOYMENT_READY: { label: 'Déploiement', hint: 'Modèle prêt pour le déploiement' }
};

const TERMINAL_RUN = ['COMPLETED', 'SUCCEEDED', 'FAILED', 'ERROR', 'CANCELLED', 'CANCELED'];

@Component({
  selector: 'app-pipeline-tracker',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './pipeline-tracker.component.html',
  styles: []
})
export class PipelineTrackerComponent {
  @Input() experiment: MlExperimentResponse | null = null;
  @Input() deployment: ModelDeploymentResponse | null = null;
  @Input() pipeline: PipelineStatusResponse | null = null;

  stepLogs: Record<string, string> = {};
  loadingLogs = false;
  logsError = '';

  constructor(private experimentService: ExperimentService) {}

  get runtimeMode(): string {
    if (!this.experiment) {
      return 'STANDARD_RUNTIME';
    }

    const raw = String(
      this.experiment.runtimeMode ??
      this.experiment.runtime_mode ??
      this.experiment['runtimeMode'] ??
      this.experiment['runtime_mode'] ??
      this.pipeline?.runtimeMode ??
      'STANDARD_RUNTIME'
    ).trim().toUpperCase();

    if (['CUSTOM_DOCKERFILE', 'CUSTOM_DOCKER', 'CUSTOM_IMAGE'].includes(raw)) {
      return 'CUSTOM_DOCKERFILE';
    }

    return 'STANDARD_RUNTIME';
  }

  get isDockerMode(): boolean {
    return this.runtimeMode === 'CUSTOM_DOCKERFILE';
  }

  get runtimeLabel(): string {
    return this.isDockerMode
      ? 'Runtime dockerisé · Kaniko + Kubernetes'
      : 'Runtime standard · Kubeflow Pipelines';
  }

  get status(): string {
    return String(
      this.pipeline?.runStatus ??
      this.experiment?.status ??
      ''
    ).toUpperCase();
  }

  get kubeflowStatus(): string {
    return String(
      this.pipeline?.kubeflowStatus ??
      this.experiment?.['kubeflowStatus'] ??
      this.experiment?.['kubeflow_status'] ??
      ''
    ).toUpperCase();
  }

  get buildStatus(): string {
    return String(
      this.pipeline?.buildStatus ??
      this.experiment?.buildStatus ??
      this.experiment?.['build_status'] ??
      ''
    ).toUpperCase();
  }

  get deploymentStatus(): string {
    return String(this.deployment?.deploymentStatus ?? '').toUpperCase();
  }

  get hasDeployment(): boolean {
    return !!this.deployment;
  }

  get isDeploymentRunning(): boolean {
    return ['PENDING', 'DEPLOYING'].includes(this.deploymentStatus);
  }

  get isDeploymentDone(): boolean {
    return this.deploymentStatus === 'DEPLOYED';
  }

  get isDeploymentFailed(): boolean {
    return this.deploymentStatus === 'FAILED';
  }

  get isTerminalSuccess(): boolean {
    return ['COMPLETED', 'SUCCEEDED'].includes(this.status);
  }

  get isFailed(): boolean {
    return ['FAILED', 'ERROR', 'CANCELLED', 'CANCELED'].includes(this.status);
  }

  get hasModelVersion(): boolean {
    const version = this.experiment?.modelVersion ?? this.experiment?.mlflowModelVersion;
    return version != null && String(version).trim() !== '';
  }

  get usesBackendSteps(): boolean {
    return !!this.pipeline && Array.isArray(this.pipeline.steps) && this.pipeline.steps.length > 0;
  }

  get steps(): PipelineStep[] {
    if (!this.experiment) {
      return [];
    }

    if (this.usesBackendSteps) {
      return this.buildBackendSteps();
    }

    return this.buildFallbackSteps();
  }

  private buildBackendSteps(): PipelineStep[] {
    const definitions = this.isDockerMode ? CUSTOM_PIPELINE : STANDARD_PIPELINE;

    return (this.pipeline?.steps || []).map((step: RunPipelineStepDto) => {
      const definition = definitions[step.stepCode] || {
        label: step.label || step.stepCode,
        hint: ''
      };

      return {
        key: step.stepCode,
        label: definition.label,
        hint: definition.hint,
        state: this.mapBackendStatus(step.status),
        duration: this.formatDuration(step.startedAt, step.completedAt),
        errorCode: step.errorCode || undefined,
        jobName: step.jobName || undefined,
        hasLogs: !!step.hasLogs || !!step.message
      };
    });
  }

  private mapBackendStatus(
    rawStatus: string | undefined
  ): PipelineStep['state'] {
    switch (String(rawStatus || '').toUpperCase()) {
      case 'SUCCEEDED':
        return 'done';
      case 'RUNNING':
        return 'active';
      case 'FAILED':
        return 'failed';
      case 'SKIPPED':
        return 'skipped';
      default:
        return 'pending';
    }
  }

  get progressPercent(): number {
    if (!this.experiment) {
      return 0;
    }

    if (this.usesBackendSteps && this.pipeline?.globalProgress != null) {
      return Math.max(0, Math.min(100, this.pipeline.globalProgress));
    }

    const steps = this.buildFallbackSteps();
    const total = steps.length;

    if (this.isFailed || this.isDeploymentFailed) {
      const failedIndex = steps.findIndex((step) => step.state === 'failed');
      const anchor = failedIndex === -1 ? total : failedIndex + 1;
      return Math.round((anchor / total) * 100);
    }

    const doneCount = steps.filter((step) => step.state === 'done').length;

    if (this.isDeploymentDone) {
      return 100;
    }

    return Math.round((doneCount / total) * 100);
  }

  get statusLabel(): string {
    if (this.isDeploymentDone) {
      return 'Modèle déployé et actif';
    }

    if (this.isDeploymentFailed) {
      return 'Échec du déploiement';
    }

    if (this.isDeploymentRunning) {
      return 'Déploiement du modèle en cours...';
    }

    /*
     * Source de vérité : les étapes du backend. On ne déduit plus le statut
     * uniquement du statut global de l'expérience (qui peut être COMPLETED
     * alors que Production/Déploiement sont SKIPPED).
     */
    if (this.usesBackendSteps) {
      const failedStep = this.steps.find((step) => step.state === 'failed');

      if (failedStep) {
        return 'Pipeline en échec';
      }

      const productionSkipped = this.steps.some(
        (step) =>
          (step.key === 'PRODUCTION_PROMOTED' || step.key === 'DEPLOYMENT_READY') &&
          step.state === 'skipped'
      );

      if (this.isTerminalSuccess) {
        if (productionSkipped) {
          return 'Entraînement terminé — modèle non promu en production';
        }

        if (this.steps.every((step) => step.state === 'done')) {
          return 'Pipeline terminé avec succès';
        }

        return 'Modèle entraîné — en attente de déploiement automatique...';
      }

      const runningCount = this.steps.filter((step) => step.state === 'active').length;

      if (runningCount > 0) {
        return this.isDockerMode
          ? 'Entraînement Kubernetes en cours...'
          : 'Exécution du pipeline Kubeflow...';
      }
    }

    if (this.isTerminalSuccess) {
      return this.hasDeployment
        ? 'Entraînement terminé avec succès'
        : 'Modèle entraîné — en attente de déploiement automatique...';
    }

    if (this.isFailed) {
      return 'Pipeline en échec';
    }

    const runningCount = this.steps.filter((step) => step.state === 'active').length;
    if (runningCount > 0) {
      return this.isDockerMode
        ? 'Entraînement Kubernetes en cours...'
        : 'Exécution du pipeline Kubeflow...';
    }

    switch (this.status) {
      case 'CREATED':
        return this.isDockerMode
          ? 'Soumission validée — démarrage du build de l’image...'
          : 'Soumission validée — préparation du pipeline Kubeflow...';
      case 'RUNNING':
        return this.isDockerMode
          ? 'Entraînement Kubernetes en cours...'
          : 'Exécution du pipeline Kubeflow...';
      default:
        return 'Pipeline en cours...';
    }
  }

  get statusBadgeClass(): string {
    if (this.isDeploymentDone) {
      return 'bg-success-100 text-success-700 dark:bg-success-900/30 dark:text-success-300';
    }
    if (this.isFailed || this.isDeploymentFailed) {
      return 'bg-error-100 text-error-700 dark:bg-error-900/30 dark:text-error-300';
    }
    if (
      this.usesBackendSteps &&
      this.isTerminalSuccess &&
      this.steps.some(
        (step) =>
          (step.key === 'PRODUCTION_PROMOTED' || step.key === 'DEPLOYMENT_READY') &&
          step.state === 'skipped'
      )
    ) {
      return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300';
    }
    return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300';
  }

  get isLive(): boolean {
    return this.isDeploymentDone;
  }

  get logSnippet(): string {
    const logs =
      this.experiment?.buildLogs ??
      this.experiment?.['build_logs'] ??
      this.experiment?.['trainingLogs'] ??
      this.experiment?.['training_logs'];

    if (!logs || !logs.trim()) {
      return '';
    }

    const lines = String(logs).split('\n').map((line) => line.trim()).filter(Boolean);

    if (lines.length <= 6) {
      return lines.join('\n');
    }

    return lines.slice(0, 6).join('\n') + '\n…';
  }

  formatDate(value?: string | null): string {
    if (!value) {
      return '';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
      return '';
    }

    return date.toLocaleTimeString();
  }

  formatDuration(startedAt?: string | null, completedAt?: string | null): string {
    if (!startedAt) {
      return '';
    }

    const start = new Date(startedAt).getTime();
    const end = completedAt ? new Date(completedAt).getTime() : Date.now();

    if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
      return '';
    }

    const seconds = Math.round((end - start) / 1000);

    if (seconds < 60) {
      return `${seconds}s`;
    }

    const minutes = Math.floor(seconds / 60);
    const rest = seconds % 60;

    return `${minutes}m ${rest}s`;
  }

  loadStepLogs(step: PipelineStep): void {
    if (!this.experiment?.id || this.loadingLogs) {
      return;
    }

    if (this.stepLogs[step.key] !== undefined) {
      delete this.stepLogs[step.key];
      return;
    }

    this.loadingLogs = true;
    this.logsError = '';

    this.experimentService.getPipelineStepLogs(this.experiment.id, step.key).subscribe({
      next: (logs: string) => {
        this.loadingLogs = false;
        this.stepLogs[step.key] = logs || 'Aucun log disponible pour cette étape.';
      },
      error: () => {
        this.loadingLogs = false;
        this.logsError = 'Impossible de charger les logs de cette étape.';
      }
    });
  }

  hasLoadedLogs(step: PipelineStep): boolean {
    return this.stepLogs[step.key] !== undefined;
  }

  getStepMessage(step: PipelineStep): string {
    const backendStep = (this.pipeline?.steps || []).find(
      (item) => item.stepCode === step.key
    );
    return backendStep?.message || '';
  }

  isTerminalRunStatus(status: string | undefined): boolean {
    return TERMINAL_RUN.includes(String(status || '').toUpperCase());
  }

  /* ------------------------------------------------------------------ */
  /* Fallback heuristique (backend ancien sans pipeline-status)          */
  /* ------------------------------------------------------------------ */

  private readonly buildStarted = [
    'WAITING_FOR_IMAGE_BUILD',
    'IMAGE_BUILD_RUNNING',
    'CUSTOM_IMAGE_JOB_STARTING',
    'CUSTOM_IMAGE_JOB_RUNNING'
  ];

  private readonly buildDone = [
    'IMAGE_BUILD_COMPLETED',
    'CUSTOM_IMAGE_JOB_COMPLETED'
  ];

  private readonly buildFailed = [
    'IMAGE_BUILD_FAILED',
    'CUSTOM_IMAGE_JOB_FAILED',
    'CUSTOM_DOCKER_FAILED',
    'FAILED'
  ];

  private buildFallbackSteps(): PipelineStep[] {
    const steps = this.isDockerMode
      ? this.buildFallbackDockerSteps()
      : this.buildFallbackStandardSteps();

    if (this.isFailed) {
      this.markFailure(steps);
    }

    if (this.isDeploymentFailed) {
      this.markDeploymentFailure(steps);
    }

    return steps;
  }

  private buildFallbackStandardSteps(): PipelineStep[] {
    const kubeflowDone = this.isTerminalSuccess || this.kubeflowStatus === 'COMPLETED';
    const kubeflowActive =
      !kubeflowDone && (this.status === 'RUNNING' || this.kubeflowStatus === 'RUNNING');

    return [
      this.step('SUBMIT', 'Soumission', 'Fichiers et configuration validés', this.submitState()),
      this.step('KUBEFLOW', 'Pipeline Kubeflow', 'Run Kubeflow soumis et exécuté',
        this.phaseState(kubeflowDone, kubeflowActive)),
      this.step('REGISTRY', 'Registry MLflow', 'Modèle enregistré', this.registryState()),
      this.step('DEPLOY', 'Déploiement', 'Image de service en cours de déploiement', this.deployState()),
      this.step('LIVE', 'Service actif', 'Endpoint de prédiction disponible', this.liveState())
    ];
  }

  private buildFallbackDockerSteps(): PipelineStep[] {
    const buildDone = this.buildDone.includes(this.buildStatus);
    const buildActive = this.buildStarted.includes(this.buildStatus) ||
      (this.buildStatus === '' && this.status === 'CREATED');

    const trainDone = this.isTerminalSuccess || this.isFailed;
    const trainActive = !trainDone && (this.status === 'RUNNING' || this.status === 'CREATED');

    return [
      this.step('SUBMIT', 'Soumission', 'Fichiers et configuration validés', this.submitState()),
      this.step('BUILD', 'Build image (Kaniko)', 'Job Kaniko de construction de l’image Docker',
        this.buildState(buildDone, buildActive)),
      this.step('TRAIN', 'Entraînement Kubernetes', 'Pod d’entraînement exécuté avec l’image custom',
        this.phaseState(trainDone, trainActive)),
      this.step('REGISTRY', 'Registry MLflow', 'Modèle enregistré', this.registryState()),
      this.step('DEPLOY', 'Déploiement', 'Image de service en cours de déploiement', this.deployState()),
      this.step('LIVE', 'Service actif', 'Endpoint de prédiction disponible', this.liveState())
    ];
  }

  private step(
    key: string,
    label: string,
    hint: string,
    state: PipelineStep['state']
  ): PipelineStep {
    return { key, label, hint, state };
  }

  private submitState(): 'done' | 'active' {
    const progressed = this.isTerminalSuccess || this.isFailed ||
      this.status === 'RUNNING' || this.status === 'CREATED' ||
      this.buildStatus !== '' || this.kubeflowStatus !== '';
    return progressed ? 'done' : 'active';
  }

  private phaseState(done: boolean, active: boolean): 'done' | 'active' | 'pending' {
    if (done) {
      return 'done';
    }
    if (active) {
      return 'active';
    }
    return 'pending';
  }

  private buildState(done: boolean, active: boolean): PipelineStep['state'] {
    if (this.buildFailed.includes(this.buildStatus)) {
      return 'failed';
    }
    if (done) {
      return 'done';
    }
    if (active) {
      return 'active';
    }
    if (this.isTerminalSuccess || this.isFailed) {
      return 'done';
    }
    return 'pending';
  }

  private registryState(): PipelineStep['state'] {
    if (this.isTerminalSuccess && this.hasModelVersion) {
      return 'done';
    }
    if (this.isTerminalSuccess) {
      return 'active';
    }
    return 'pending';
  }

  private deployState(): PipelineStep['state'] {
    if (this.isDeploymentFailed) {
      return 'failed';
    }
    if (this.isDeploymentDone) {
      return 'done';
    }
    if (this.isDeploymentRunning) {
      return 'active';
    }
    if (this.isTerminalSuccess) {
      return 'active';
    }
    return 'pending';
  }

  private liveState(): PipelineStep['state'] {
    if (this.isDeploymentDone) {
      return 'done';
    }
    if (this.isDeploymentRunning || (this.isTerminalSuccess && !this.isDeploymentFailed)) {
      return 'active';
    }
    return 'pending';
  }

  private markFailure(steps: PipelineStep[]): void {
    const failedBuildIndex = steps.findIndex((step) => step.state === 'failed');
    const anchor = failedBuildIndex === -1 ? this.currentPhaseIndex(steps) : failedBuildIndex;

    const index = anchor === -1 ? steps.length - 1 : anchor;

    steps[index].state = 'failed';

    for (let i = index + 1; i < steps.length; i++) {
      steps[i].state = 'pending';
    }
  }

  private markDeploymentFailure(steps: PipelineStep[]): void {
    const deployIndex = steps.findIndex((step) => step.key === 'DEPLOY');

    if (deployIndex !== -1) {
      steps[deployIndex].state = 'failed';
    }

    const liveIndex = steps.findIndex((step) => step.key === 'LIVE');
    if (liveIndex !== -1 && steps[liveIndex].state === 'active') {
      steps[liveIndex].state = 'pending';
    }
  }

  private currentPhaseIndex(steps: PipelineStep[]): number {
    const active = steps.findIndex((step) => step.state === 'active');
    return active === -1 ? steps.length - 1 : active;
  }
}
