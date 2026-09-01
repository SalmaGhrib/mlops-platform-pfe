import { Component, OnInit, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { Observable } from 'rxjs';

import {
  MlProjectRequest,
  MlProjectResponse,
  MlProjectMetricsRequest,
  ProjectService,
  ProjectType,
  RuntimeMode
} from '../../core/services/project.service';

import { PageBreadcrumbComponent } from '../../shared/components/common/page-breadcrumb/page-breadcrumb.component';
import { SmartSearchComponent } from '../../shared/components/common/smart-search/smart-search.component';
import {
  SearchPageResponse,
  SmartSearchParams
} from '../../shared/components/common/smart-search/smart-search.types';

import {
  RetrainingConfigRequest,
  RetrainingConfigResponse,
  RetrainingService,
  ManualRetrainingResponse,
  ProjectRetrainingStatusResponse,
  isRetrainingNonTerminalStatus
} from '../../core/services/retraining.service';

import {
  GitHubRetrainingConfigDto,
  GitHubRetrainingService,
  GitHubKeyResponse
} from '../../core/services/github-retraining.service';

type DataType = 'TABULAR' | 'TEXT' | 'IMAGE';

type ProjectFormModel = MlProjectRequest & {
  dataType: DataType;
  minPredictionsForMonitoring: number;
  performanceDropThresholdPercent: number;
  dataDriftThresholdPercent: number;
};

@Component({
  selector: 'app-my-projects',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule,
    PageBreadcrumbComponent,
    SmartSearchComponent
  ],
  templateUrl: './my-projects.component.html',
  styles: ``
})
export class MyProjectsComponent implements OnInit {
  @ViewChild('projectFormSection') projectFormSection?: ElementRef<HTMLElement>;
  @ViewChild(SmartSearchComponent) smartSearch!: SmartSearchComponent<MlProjectResponse>;

  projects: MlProjectResponse[] = [];
  totalProjects = 0;

  loading = false;
  saving = false;
  loadingMetrics = false;
  pollingStatuses = new Set<number>();
  launchingProjectIds = new Set<number>();

  errorMessage = '';
  successMessage = '';

  isFormOpen = false;
  editingProjectId: number | null = null;

  metricOptions: string[] = [];

  previousMetric1 = '';
  previousMetric2 = '';
  previousMetric3 = '';

  retrainingConfigs: Record<number, RetrainingConfigResponse | null> = {};

  githubConfigs: Record<number, GitHubRetrainingConfigDto | null> = {};
  githubDraftRepos: Record<number, string> = {};
  githubDraftBranches: Record<number, string> = {};
  githubDraftEnabled: Record<number, boolean> = {};
  githubSaving = new Set<number>();
  githubKeying = new Set<number>();
  githubRevoking = new Set<number>();
  githubWorkflowLoading = new Set<number>();
  revealedKeys: Record<number, string | null> = {};
  githubPanelOpen: number | null = null;

  projectForm: ProjectFormModel = this.getDefaultProjectForm();

  constructor(
    private projectService: ProjectService,
    private retrainingService: RetrainingService,
    private githubRetrainingService: GitHubRetrainingService
  ) {}

  ngOnInit(): void {
    this.loadProjects();
    this.loadMetricOptions('classification');
  }

  private refreshProjectStatus(projectId: number): void {
    if (!projectId || this.pollingStatuses.has(projectId)) {
      return;
    }

    this.pollingStatuses.add(projectId);

    this.retrainingService.getProjectStatus(projectId).subscribe({
      next: (status: ProjectRetrainingStatusResponse) => {
        const config = this.retrainingConfigs[projectId] ?? null;
        if (config) {
          this.retrainingConfigs[projectId] = {
            ...config,
            enabled: status?.enabled ?? config.enabled,
            running: status?.running ?? config.running,
            manualTriggerRequested: status?.manualTriggerRequested ?? config.manualTriggerRequested,
            lastDecisionReason: status?.lastDecisionReason ?? config.lastDecisionReason,
            lastTriggerReason: status?.lastTriggerReason ?? config.lastTriggerReason,
            lastRetrainingAt: status?.lastRetrainingAt ?? config.lastRetrainingAt,
          } as RetrainingConfigResponse;
        }

        const stillActive =
          status?.running === true || status?.activeExperimentExists === true;

        this.scheduleStatusPoll(projectId, stillActive ? 5000 : 0);
      },
      error: () => {
        this.scheduleStatusPoll(projectId, 0);
      }
    });
  }

  private scheduleStatusPoll(projectId: number, delayMs: number): void {
    this.pollingStatuses.delete(projectId);

    if (delayMs <= 0) {
      return;
    }

    setTimeout(() => {
      this.pollingStatuses.delete(projectId);
      this.refreshProjectStatus(projectId);
    }, delayMs);
  }

  private getDefaultProjectForm(): ProjectFormModel {
    return {
      name: '',
      description: '',
      targetColumn: '',
      projectType: 'classification',
      primaryMetric1: '',
      primaryMetric2: null,
      primaryMetric3: null,
      autoPromoteBestModel: true,
      runtimeMode: 'STANDARD_RUNTIME',
      dataType: 'TABULAR',

      minPredictionsForMonitoring: 20,
      performanceDropThresholdPercent: 10,
      dataDriftThresholdPercent: 5,
    };
  }

  private scrollToProjectForm(): void {
    setTimeout(() => {
      if (this.projectFormSection?.nativeElement) {
        this.projectFormSection.nativeElement.scrollIntoView({
          behavior: 'smooth',
          block: 'start'
        });
      } else {
        window.scrollTo({
          top: 0,
          behavior: 'smooth'
        });
      }
    }, 0);
  }

  loadProjects(): void {
    this.smartSearch?.resetAndReload();
  }

  refresh(): void {
    this.smartSearch?.resetAndReload();
  }

  searchProjects = (params: SmartSearchParams): Observable<SearchPageResponse<MlProjectResponse>> =>
    this.projectService.searchProjects(params);

  onSearchItems(projects: MlProjectResponse[]): void {
    this.projects = (projects || []).map((project) => this.normalizeProject(project));
    this.projects.forEach((project) => {
      this.loadRetrainingConfigForProjectCard(project.id);
      this.loadGitHubConfigForProjectCard(project.id);
    });
  }

  onTotalChange(total: number): void {
    this.totalProjects = total;
  }

  private loadRetrainingConfigForProjectCard(projectId: number): void {
    if (!projectId) {
      return;
    }

    this.retrainingService.getConfig(projectId).subscribe({
      next: (config: RetrainingConfigResponse) => {
        this.retrainingConfigs[projectId] =
          this.normalizeRetrainingConfig(config);
        this.refreshProjectStatus(projectId);
      },
      error: () => {
        this.retrainingConfigs[projectId] = null;
      }
    });
  }

  private loadGitHubConfigForProjectCard(projectId: number): void {
    if (!projectId) {
      return;
    }

    this.githubRetrainingService.getConfig(projectId).subscribe({
      next: (config: GitHubRetrainingConfigDto) => {
        this.githubConfigs[projectId] = config;
        this.githubDraftRepos[projectId] = config.repository || '';
        this.githubDraftBranches[projectId] = config.branch || '';
        this.githubDraftEnabled[projectId] = config.enabled === true;
      },
      error: () => {
        this.githubConfigs[projectId] = null;
      }
    });
  }

  getGitHubConfig(project: MlProjectResponse): GitHubRetrainingConfigDto | null {
    return this.githubConfigs[project.id] ?? null;
  }

  isGitHubEnabled(project: MlProjectResponse): boolean {
    return !!this.getGitHubConfig(project)?.enabled;
  }

  getGitHubRepoDraft(project: MlProjectResponse): string {
    return this.githubDraftRepos[project.id] ?? '';
  }

  getGitHubBranchDraft(project: MlProjectResponse): string {
    return this.githubDraftBranches[project.id] ?? '';
  }

  onGitHubRepoDraft(projectId: number, value: string): void {
    this.githubDraftRepos[projectId] = value;
  }

  onGitHubBranchDraft(projectId: number, value: string): void {
    this.githubDraftBranches[projectId] = value;
  }

  onGitHubEnabledDraft(projectId: number, value: boolean): void {
    this.githubDraftEnabled[projectId] = value;
  }

  saveGitHubConfig(project: MlProjectResponse): void {
    if (this.githubSaving.has(project.id)) {
      return;
    }

    const enabled = this.githubDraftEnabled[project.id] === true;

    if (enabled) {
      const repository = (this.githubDraftRepos[project.id] || '').trim();
      const branch = (this.githubDraftBranches[project.id] || '').trim();

      if (!repository) {
        this.errorMessage =
          'Indiquez le dépôt GitHub (format owner/repository).';
        return;
      }

      if (!branch) {
        this.errorMessage = 'Indiquez la branche GitHub à écouter.';
        return;
      }
    }

    const repository = (this.githubDraftRepos[project.id] || '').trim();
    const branch = (this.githubDraftBranches[project.id] || '').trim();

    this.githubSaving.add(project.id);
    this.errorMessage = '';
    this.successMessage = '';

    this.githubRetrainingService
      .saveConfig(project.id, { enabled, repository, branch })
      .subscribe({
        next: (config: GitHubRetrainingConfigDto) => {
          this.githubSaving.delete(project.id);
          this.githubConfigs[project.id] = config;
          this.githubDraftRepos[project.id] = config.repository || '';
          this.githubDraftBranches[project.id] = config.branch || '';
          this.githubDraftEnabled[project.id] = config.enabled === true;

          if (enabled) {
            this.successMessage =
              'Intégration GitHub activée : les pushs déclencheront ' +
              'automatiquement un réentraînement.';
          } else {
            this.successMessage = 'Intégration GitHub désactivée.';
          }
        },
        error: (error) => {
          this.githubSaving.delete(project.id);
          this.errorMessage = this.getBackendErrorMessage(
            error,
            'Impossible d’enregistrer la configuration GitHub.'
          );
        }
      });
  }

  generateGitHubKey(project: MlProjectResponse): void {
    if (this.githubKeying.has(project.id)) {
      return;
    }

    this.githubKeying.add(project.id);
    this.errorMessage = '';
    this.successMessage = '';

    this.githubRetrainingService.generateKey(project.id).subscribe({
      next: (key: GitHubKeyResponse) => {
        this.githubKeying.delete(project.id);
        this.revealedKeys[project.id] = key.secretKey;

        const config = this.githubConfigs[project.id];
        this.githubConfigs[project.id] = {
          ...(config as GitHubRetrainingConfigDto),
          keyConfigured: true,
          secretSuffix: key.secretSuffix
        };

        this.successMessage =
          'Clé générée. Copiez-la maintenant : elle ne sera plus ' +
          'jamais affichée.';
      },
      error: (error) => {
        this.githubKeying.delete(project.id);
        this.errorMessage = this.getBackendErrorMessage(
          error,
          'Impossible de générer la clé GitHub.'
        );
      }
    });
  }

  revokeGitHubKey(project: MlProjectResponse): void {
    const config = this.getGitHubConfig(project);

    if (!config?.keyConfigured) {
      this.errorMessage = 'Aucune clé à révoquer pour ce projet.';
      return;
    }

    const confirmed = window.confirm(
      'Révoquer la clé de retraining GitHub de ce projet ? ' +
        'Tout workflow utilisant cette clé sera refusé.'
    );

    if (!confirmed) {
      return;
    }

    if (this.githubRevoking.has(project.id)) {
      return;
    }

    this.githubRevoking.add(project.id);
    this.errorMessage = '';
    this.successMessage = '';

    this.githubRetrainingService.revokeKey(project.id).subscribe({
      next: () => {
        this.githubRevoking.delete(project.id);
        this.revealedKeys[project.id] = null;

        const current = this.githubConfigs[project.id];
        this.githubConfigs[project.id] = {
          ...(current as GitHubRetrainingConfigDto),
          keyConfigured: false,
          secretSuffix: null
        };

        this.successMessage = 'Clé GitHub révoquée avec succès.';
      },
      error: (error) => {
        this.githubRevoking.delete(project.id);
        this.errorMessage = this.getBackendErrorMessage(
          error,
          'Impossible de révoquer la clé GitHub.'
        );
      }
    });
  }

  copyGitHubWorkflow(project: MlProjectResponse): void {
    if (this.githubWorkflowLoading.has(project.id)) {
      return;
    }

    this.githubWorkflowLoading.add(project.id);
    this.errorMessage = '';
    this.successMessage = '';

    this.githubRetrainingService.getWorkflow(project.id).subscribe({
      next: (workflow) => {
        this.githubWorkflowLoading.delete(project.id);
        this.copyToClipboard(workflow.workflow);
        this.successMessage =
          'Workflow GitHub Actions copié. Créez ' +
          '.github/workflows/retraining.yml dans votre dépôt et ' +
          'collez-le.';
      },
      error: (error) => {
        this.githubWorkflowLoading.delete(project.id);
        this.errorMessage = this.getBackendErrorMessage(
          error,
          'Impossible de récupérer le workflow GitHub.'
        );
      }
    });
  }

  hideRevealedGitHubKey(project: MlProjectResponse): void {
    this.revealedKeys[project.id] = null;
  }

  copyToClipboard(text: string): void {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    document.body.removeChild(textarea);
  }

  getGitHubStatusLabel(config: GitHubRetrainingConfigDto | null): string {
    if (!config) {
      return 'Non configuré';
    }

    if (config.enabled !== true) {
      return config.keyConfigured
        ? 'Désactivé (clé configurée)'
        : 'Désactivé';
    }

    return config.repository
      ? config.repository + ' @ ' + (config.branch || 'main')
      : 'Activé, dépôt non défini';
  }

  getGitHubKeyBadge(config: GitHubRetrainingConfigDto | null): string {
    if (!config?.keyConfigured) {
      return 'Aucune clé';
    }

    const suffix = config.secretSuffix || '****';
    return 'Clé configurée · …' + suffix;
  }

  getGitHubLastTriggerLabel(
    config: GitHubRetrainingConfigDto | null
  ): string {
    if (!config) {
      return '';
    }

    const parts: string[] = [];

    if (config.lastTriggerAt) {
      parts.push(this.formatDateTime(config.lastTriggerAt));
    }

    const status = config.lastTriggerStatus;
    const message = config.lastTriggerMessage;

    if (status && status !== 'ACCEPTED') {
      parts.push(status);
    }

    if (message) {
      parts.push(message);
    }

    return parts.join(' · ');
  }

  openCreateForm(): void {
    this.isFormOpen = true;
    this.editingProjectId = null;
    this.errorMessage = '';
    this.successMessage = '';

    this.projectForm = this.getDefaultProjectForm();

    this.loadMetricOptions('classification');
    this.scrollToProjectForm();
  }

  openEditForm(project: MlProjectResponse): void {
    this.isFormOpen = true;
    this.editingProjectId = project.id;
    this.errorMessage = '';
    this.successMessage = '';

    this.projectForm = {
      name: project.name || '',
      description: project.description || '',
      targetColumn: project.targetColumn || '',
      projectType: project.projectType || 'classification',
      primaryMetric1: project.primaryMetric1 || '',
      primaryMetric2: project.primaryMetric2 || null,
      primaryMetric3: project.primaryMetric3 || null,
      autoPromoteBestModel: project.autoPromoteBestModel ?? true,
      runtimeMode: this.getProjectRuntimeMode(project),
      dataType: this.getProjectDataType(project),

      minPredictionsForMonitoring: 20,
      performanceDropThresholdPercent: 10,
      dataDriftThresholdPercent: 5,
    };

    this.loadRetrainingConfigIntoForm(project.id);

    this.savePreviousMetricValues();

    this.loadMetricOptions(
      this.projectForm.projectType ?? 'classification',
      project.primaryMetric1,
      project.primaryMetric2,
      project.primaryMetric3
    );

    this.scrollToProjectForm();
  }

  private loadRetrainingConfigIntoForm(projectId: number): void {
    this.retrainingService.getConfig(projectId).subscribe({
      next: (rawConfig: RetrainingConfigResponse) => {
        const config = this.normalizeRetrainingConfig(rawConfig);
        this.retrainingConfigs[projectId] = config;

        this.projectForm.minPredictionsForMonitoring =
          config.minPredictionsForMonitoring || 20;
        this.projectForm.performanceDropThresholdPercent =
          config.performanceDropThresholdPercent ?? 10;
        this.projectForm.dataDriftThresholdPercent =
          config.dataDriftThresholdPercent ?? 5;
      },
      error: () => {
        this.retrainingConfigs[projectId] = null;
      }
    });
  }

  closeForm(): void {
    this.isFormOpen = false;
    this.editingProjectId = null;
    this.errorMessage = '';

    this.projectForm = this.getDefaultProjectForm();

    this.metricOptions = [];
    this.savePreviousMetricValues();
  }

  onProjectTypeChange(projectType: ProjectType): void {
    if (this.editingProjectId !== null) {
      return;
    }

    this.projectForm.projectType = projectType;
    this.projectForm.primaryMetric1 = '';
    this.projectForm.primaryMetric2 = null;
    this.projectForm.primaryMetric3 = null;

    this.loadMetricOptions(projectType);
  }

  onRuntimeModeChange(runtimeMode: RuntimeMode): void {
    this.projectForm.runtimeMode = runtimeMode;
  }

  onDataTypeChange(dataType: DataType): void {
    if (this.editingProjectId !== null) {
      return;
    }

    this.projectForm.dataType = this.normalizeDataType(dataType);
  }


  loadMetricOptions(
    projectType: ProjectType,
    selectedMetric1?: string | null,
    selectedMetric2?: string | null,
    selectedMetric3?: string | null
  ): void {
    this.loadingMetrics = true;

    this.projectService.getMetricOptions(projectType).subscribe({
      next: (metrics: string[]) => {
        this.metricOptions = metrics || [];
        this.loadingMetrics = false;

        if (
          selectedMetric1 !== undefined ||
          selectedMetric2 !== undefined ||
          selectedMetric3 !== undefined
        ) {
          this.projectForm.primaryMetric1 = (selectedMetric1 ?? this.metricOptions[0]) || '';
          this.projectForm.primaryMetric2 = selectedMetric2 || null;
          this.projectForm.primaryMetric3 = selectedMetric3 || null;
        } else {
          this.applyDefaultMetrics(projectType);
        }

        this.ensureDistinctMetrics();
        this.savePreviousMetricValues();
      },
      error: (error) => {
        console.error('Load metric options error:', error);
        this.loadingMetrics = false;
        this.metricOptions = this.getFallbackMetricOptions(projectType);
        this.applyDefaultMetrics(projectType);
        this.ensureDistinctMetrics();
        this.savePreviousMetricValues();
      }
    });
  }

  private applyDefaultMetrics(projectType: ProjectType): void {
    const fallbackMetrics = this.metricOptions.length > 0
      ? this.metricOptions
      : this.getFallbackMetricOptions(projectType);

    this.projectForm.primaryMetric1 = fallbackMetrics[0] || '';
    this.projectForm.primaryMetric2 = null;
    this.projectForm.primaryMetric3 = null;
  }

  onMetricChange(metricIndex: 1 | 2 | 3): void {
    const selectedMetric =
      metricIndex === 1
        ? this.projectForm.primaryMetric1
        : metricIndex === 2
          ? this.projectForm.primaryMetric2
          : this.projectForm.primaryMetric3;

    if (!selectedMetric) {
      this.savePreviousMetricValues();
      return;
    }

    const duplicates = [
      this.projectForm.primaryMetric1,
      this.projectForm.primaryMetric2,
      this.projectForm.primaryMetric3
    ].filter((metric) => metric && metric === selectedMetric);

    if (duplicates.length > 1) {
      const previousValue =
        metricIndex === 1
          ? this.previousMetric1
          : metricIndex === 2
            ? this.previousMetric2
            : this.previousMetric3;

      if (metricIndex === 1) {
        this.projectForm.primaryMetric1 = previousValue;
      } else if (metricIndex === 2) {
        this.projectForm.primaryMetric2 = previousValue || null;
      } else {
        this.projectForm.primaryMetric3 = previousValue || null;
      }

      this.errorMessage = 'Chaque métrique doit être choisie une seule fois.';

      setTimeout(() => {
        if (this.errorMessage === 'Chaque métrique doit être choisie une seule fois.') {
          this.errorMessage = '';
        }
      }, 2500);

      return;
    }

    this.savePreviousMetricValues();
  }

  getAvailableMetricsFor(metricIndex: 1 | 2 | 3): string[] {
    const currentMetric =
      metricIndex === 1
        ? this.projectForm.primaryMetric1
        : metricIndex === 2
          ? this.projectForm.primaryMetric2
          : this.projectForm.primaryMetric3;

    const selectedByOthers = [
      metricIndex !== 1 ? this.projectForm.primaryMetric1 : null,
      metricIndex !== 2 ? this.projectForm.primaryMetric2 : null,
      metricIndex !== 3 ? this.projectForm.primaryMetric3 : null
    ].filter(Boolean);

    return this.metricOptions.filter((metric) => {
      return metric === currentMetric || !selectedByOthers.includes(metric);
    });
  }

  private validateMetricPriorities(): boolean {
    const activeMetrics = [
      this.projectForm.primaryMetric1,
      this.projectForm.primaryMetric2,
      this.projectForm.primaryMetric3
    ].filter((metric) => metric && metric.trim() !== '');

    if (activeMetrics.length === 0) {
      this.errorMessage = 'Vous devez choisir au moins une métrique.';
      return false;
    }

    if (!this.projectForm.primaryMetric1) {
      this.errorMessage = 'La priorité 1 est obligatoire.';
      return false;
    }

    const uniqueMetrics = new Set(activeMetrics);

    if (uniqueMetrics.size !== activeMetrics.length) {
      this.errorMessage = 'Les métriques doivent être différentes.';
      return false;
    }

    return true;
  }

  private validateRetrainingConfig(): boolean {
    if (
      !this.projectForm.minPredictionsForMonitoring ||
      this.projectForm.minPredictionsForMonitoring <= 0
    ) {
      this.errorMessage =
        'Le nombre minimum de prédictions doit être supérieur à 0.';
      return false;
    }

    if (
      this.projectForm.performanceDropThresholdPercent < 0 ||
      this.projectForm.performanceDropThresholdPercent > 100
    ) {
      this.errorMessage =
        'Le seuil de baisse de performance doit être entre 0 et 100 %.';
      return false;
    }

    if (
      this.projectForm.dataDriftThresholdPercent < 0 ||
      this.projectForm.dataDriftThresholdPercent > 100
    ) {
      this.errorMessage =
        'Le seuil de data drift doit être entre 0 et 100 %.';
      return false;
    }

    return true;
  }

  saveProject(): void {
    this.errorMessage = '';
    this.successMessage = '';

    if (!this.projectForm.name || !this.projectForm.name.trim()) {
      this.errorMessage = 'Le nom du projet est obligatoire.';
      return;
    }

    if (!this.projectForm.projectType) {
      this.errorMessage = 'Le type du projet est obligatoire.';
      return;
    }

    if (!this.projectForm.dataType) {
      this.errorMessage = 'Le type de données est obligatoire.';
      return;
    }

    this.ensureDistinctMetrics();

    if (!this.validateMetricPriorities()) {
      return;
    }

    if (!this.validateRetrainingConfig()) {
      return;
    }

    const finalDataType = this.projectForm.dataType || 'TABULAR';

    const createPayload: MlProjectRequest = {
      name: this.projectForm.name.trim(),
      description: this.projectForm.description?.trim() || '',
      targetColumn: this.projectForm.targetColumn?.trim() || null,
      projectType: this.projectForm.projectType,
      primaryMetric1: this.projectForm.primaryMetric1,
      primaryMetric2: this.projectForm.primaryMetric2 || null,
      primaryMetric3: this.projectForm.primaryMetric3 || null,
      autoPromoteBestModel: this.projectForm.autoPromoteBestModel ?? true,
      runtimeMode: this.projectForm.runtimeMode || 'STANDARD_RUNTIME',
      dataType: finalDataType,
      data_type: finalDataType
    } as any;

    this.saving = true;

    if (this.editingProjectId !== null) {
      const editingProjectId = this.editingProjectId;

      const projectPayload: Partial<MlProjectRequest> = {
        name: this.projectForm.name.trim(),
        description: this.projectForm.description?.trim() || '',
        targetColumn: this.projectForm.targetColumn?.trim() || null,
        autoPromoteBestModel: this.projectForm.autoPromoteBestModel ?? true,
        runtimeMode: this.projectForm.runtimeMode || 'STANDARD_RUNTIME',
        dataType: finalDataType,
        data_type: finalDataType
      } as any;

      const metricsPayload: MlProjectMetricsRequest = {
        primaryMetric1: this.projectForm.primaryMetric1,
        primaryMetric2: this.projectForm.primaryMetric2 || null,
        primaryMetric3: this.projectForm.primaryMetric3 || null,
        autoPromoteBestModel: this.projectForm.autoPromoteBestModel ?? true
      };

      this.projectService.updateProject(editingProjectId, projectPayload).subscribe({
        next: () => {
          this.projectService.updateProjectMetrics(editingProjectId, metricsPayload).subscribe({
            next: (updatedProject: MlProjectResponse) => {
              this.saveRetrainingConfigForProject(
                editingProjectId,
                () => {
                  this.saving = false;
                  this.successMessage = 'Projet modifié avec succès. La configuration du réentraînement a été mise à jour.';

                  this.projects = this.projects.map((project) =>
                    project.id === updatedProject.id
                      ? this.normalizeProject(updatedProject)
                      : project
                  );

                  this.closeForm();
                  this.refresh();
                },
                'Projet modifié, mais impossible de mettre à jour le réentraînement automatique.'
              );
            },
            error: (error) => {
              console.error('Update project metrics error:', error);
              this.saving = false;
              this.errorMessage = this.getBackendErrorMessage(
                error,
                'Projet modifié, mais impossible de mettre à jour la priorité des métriques.'
              );
            }
          });
        },
        error: (error) => {
          console.error('Update project error:', error);
          this.saving = false;
          this.errorMessage = this.getBackendErrorMessage(
            error,
            'Impossible de modifier le projet.'
          );
        }
      });

      return;
    }

    this.projectService.createProject(createPayload).subscribe({
      next: (createdProject: MlProjectResponse) => {
        const normalizedCreatedProject = this.normalizeProject(createdProject);

        this.saveRetrainingConfigForProject(
          normalizedCreatedProject.id,
          () => {
            this.saving = false;
            this.successMessage = 'Projet créé avec succès. Le monitoring est actif.';

            this.projects = [normalizedCreatedProject, ...this.projects];
            this.closeForm();
            this.refresh();
          },
          'Projet créé, mais impossible de configurer le réentraînement automatique.'
        );
      },
      error: (error) => {
        console.error('Create project error:', error);
        this.saving = false;
        this.errorMessage = this.getBackendErrorMessage(
          error,
          'Impossible de créer le projet.'
        );
      }
    });
  }

  private saveRetrainingConfigForProject(
    projectId: number,
    onSuccess: () => void,
    fallbackErrorMessage: string
  ): void {
    const request: RetrainingConfigRequest = {
      enabled: true,
      strategy: 'MONITORING_SMART',

      minPredictionsForMonitoring:
        this.projectForm.minPredictionsForMonitoring || 20,
      performanceDropThresholdPercent:
        this.projectForm.performanceDropThresholdPercent ?? 10,
      dataDriftThresholdPercent:
        this.projectForm.dataDriftThresholdPercent ?? 5,
    };

    this.retrainingService.saveProjectConfig(projectId, request).subscribe({
      next: (config: RetrainingConfigResponse) => {
        this.retrainingConfigs[projectId] =
          this.normalizeRetrainingConfig(config);
        onSuccess();
      },
      error: (error) => {
        console.error('Save retraining config error:', error);
        this.saving = false;
        this.errorMessage = this.getBackendErrorMessage(
          error,
          fallbackErrorMessage
        );
      }
    });
  }

  forceRetrainingNow(project: MlProjectResponse): void {
    const config = this.retrainingConfigs[project.id];

    if (!config?.enabled) {
      this.errorMessage =
        'Le réentraînement n\u2019est pas encore configuré pour ce projet. Enregistrez d\u2019abord le projet.';
      return;
    }

    if (config.running || config.manualTriggerRequested) {
      this.errorMessage =
        'Un réentraînement est déjà en cours ou déjà demandé pour ce projet.';
      return;
    }

    this.launchingProjectIds.add(project.id);
    this.errorMessage = '';
    this.successMessage = '';

    this.retrainingService.forceNow(project.id).subscribe({
      next: (response: ManualRetrainingResponse) => {
        this.launchingProjectIds.delete(project.id);

        const success = response?.success === true;
        const nonTerminal = isRetrainingNonTerminalStatus(response?.status);

        const updatedConfig = this.normalizeRetrainingConfig({
          ...(config as RetrainingConfigResponse),
          projectId: project.id,
          enabled: config.enabled,
          running: success && nonTerminal,
          manualTriggerRequested: false,
          lastTriggerReason: 'MANUAL',
          lastDecisionReason:
            response?.message ||
            (success
              ? 'Réentraînement manuel lancé.'
              : 'Échec du lancement manuel.'),
          nextRetrainingAt: null,
          experimentId: response?.experimentId ?? null,
          jobName: response?.jobName ?? null,
          kubeflowRunName: response?.jobName ?? null
        } as RetrainingConfigResponse);

        this.retrainingConfigs[project.id] = updatedConfig;

        if (success) {
          this.successMessage =
            response?.message || 'Réentraînement lancé avec succès.';
          this.refreshProjectStatus(project.id);
        } else {
          this.errorMessage =
            response?.message || 'Impossible de lancer le réentraînement.';
          this.refreshProjectStatus(project.id);
        }
      },
      error: (error) => {
        this.launchingProjectIds.delete(project.id);

        const status = error?.status;
        const backendCode = error?.error?.code;

        let message: string;
        if (
          status === 409 ||
          backendCode === 'RETRAINING_ALREADY_RUNNING'
        ) {
          message =
            'Un réentraînement est déjà en cours pour ce projet.';
        } else if (
          status === 422 ||
          backendCode === 'BLOCKED_NO_SOURCE'
        ) {
          message =
            error?.error?.message ||
            'Réentraînement impossible : aucun modèle Production ' +
              'valide n\'a été trouvé pour ce projet.';
        } else if (status === 401 || status === 403) {
          message =
            'Session expirée. Reconnectez-vous puis réessayez.';
        } else {
          message = this.getBackendErrorMessage(
            error,
            'Impossible de demander le réentraînement maintenant.'
          );
        }

        this.errorMessage = message;
        this.refreshProjectStatus(project.id);
      }
    });
  }

  deleteProject(project: MlProjectResponse): void {
    const confirmed = window.confirm(
      `Voulez-vous vraiment supprimer le projet "${project.name}" ?`
    );

    if (!confirmed) {
      return;
    }

    this.projectService.deleteProject(project.id).subscribe({
      next: () => {
        this.successMessage = 'Projet supprimé avec succès.';
        this.projects = this.projects.filter((item) => item.id !== project.id);
        delete this.retrainingConfigs[project.id];
        delete this.githubConfigs[project.id];
        this.smartSearch?.reload();
      },
      error: (error) => {
        console.error('Delete project error:', error);
        this.errorMessage = this.getBackendErrorMessage(
          error,
          'Impossible de supprimer le projet.'
        );
      }
    });
  }

  private ensureDistinctMetrics(): void {
    const selected = [
      this.projectForm.primaryMetric1,
      this.projectForm.primaryMetric2,
      this.projectForm.primaryMetric3
    ].filter(Boolean) as string[];

    const unique = Array.from(new Set(selected));

    if (unique.length < selected.length) {
      const availableMetrics = this.metricOptions.length > 0
        ? this.metricOptions
        : this.getFallbackMetricOptions(this.projectForm.projectType);

      const result: string[] = [];

      for (const metric of selected) {
        if (metric && !result.includes(metric)) {
          result.push(metric);
        }
      }

      for (const metric of availableMetrics) {
        if (result.length >= 3) {
          break;
        }

        if (!result.includes(metric)) {
          result.push(metric);
        }
      }

      this.projectForm.primaryMetric1 = result[0] || '';
      this.projectForm.primaryMetric2 = result[1] || null;
      this.projectForm.primaryMetric3 = result[2] || null;
    }
  }

  private savePreviousMetricValues(): void {
    this.previousMetric1 = this.projectForm.primaryMetric1 || '';
    this.previousMetric2 = this.projectForm.primaryMetric2 || '';
    this.previousMetric3 = this.projectForm.primaryMetric3 || '';
  }

  private getFallbackMetricOptions(projectType: ProjectType | string): string[] {
    if (projectType === 'regression') {
      return ['rmse', 'mae', 'mse', 'r2'];
    }

    return ['f1_score', 'accuracy', 'precision', 'recall'];
  }

  private normalizeProject(project: MlProjectResponse): MlProjectResponse {
    return {
      ...project,
      runtimeMode: (project.runtimeMode || 'STANDARD_RUNTIME') as RuntimeMode,
      customImageUri: (project as any).customImageUri ?? null,
      dockerfilePath: (project as any).dockerfilePath ?? null,
      requirementsPath: (project as any).requirementsPath ?? null,
      dataType: this.getProjectDataType(project)
    } as MlProjectResponse;
  }

  private normalizeDataType(value: any): DataType {
    const normalized = String(value || 'TABULAR').trim().toUpperCase();

    if (normalized === 'TEXT' || normalized === 'NLP' || normalized === 'LLM') {
      return 'TEXT';
    }

    if (normalized === 'IMAGE' || normalized === 'VISION' || normalized === 'IMAGES') {
      return 'IMAGE';
    }

    return 'TABULAR';
  }

  getProjectDataType(project: MlProjectResponse | any | null | undefined): DataType {
    return this.normalizeDataType(
      project?.dataType ??
      project?.data_type ??
      project?.inputType ??
      project?.input_type ??
      'TABULAR'
    );
  }

  getDataTypeLabel(dataType: DataType | string | null | undefined): string {
    const value = this.normalizeDataType(dataType);

    if (value === 'TEXT') {
      return 'Texte / NLP';
    }

    if (value === 'IMAGE') {
      return 'Image';
    }

    return 'Tabulaire';
  }

  getDataTypeClass(dataType: DataType | string | null | undefined): string {
    const value = this.normalizeDataType(dataType);

    if (value === 'TEXT') {
      return 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-100 dark:bg-emerald-900/20 dark:text-emerald-300 dark:ring-emerald-900/30';
    }

    if (value === 'IMAGE') {
      return 'bg-cyan-50 text-cyan-700 ring-1 ring-cyan-100 dark:bg-cyan-900/20 dark:text-cyan-300 dark:ring-cyan-900/30';
    }

    return 'bg-gray-50 text-gray-700 ring-1 ring-gray-100 dark:bg-gray-900/40 dark:text-gray-300 dark:ring-gray-800';
  }

  getProjectRuntimeMode(project: MlProjectResponse | null | undefined): RuntimeMode {
    const value = String(project?.runtimeMode || 'STANDARD_RUNTIME').toUpperCase();

    if (value === 'CUSTOM_DOCKERFILE') {
      return 'CUSTOM_DOCKERFILE';
    }

    return 'STANDARD_RUNTIME';
  }

  isCustomDockerfileProject(project: MlProjectResponse): boolean {
    return this.getProjectRuntimeMode(project) === 'CUSTOM_DOCKERFILE';
  }

  getRuntimeModeLabel(
    projectOrMode: MlProjectResponse | RuntimeMode | string | null | undefined
  ): string {
    const mode = typeof projectOrMode === 'object'
      ? this.getProjectRuntimeMode(projectOrMode)
      : String(projectOrMode || 'STANDARD_RUNTIME').toUpperCase();

    if (mode === 'CUSTOM_DOCKERFILE') {
      return 'Dockerfile personnalisé';
    }

    return 'Runtime standard';
  }

  getRuntimeModeClass(project: MlProjectResponse): string {
    return this.isCustomDockerfileProject(project)
      ? 'bg-purple-50 text-purple-700 ring-1 ring-purple-100 dark:bg-purple-900/20 dark:text-purple-300 dark:ring-purple-900/30'
      : 'bg-blue-50 text-blue-700 ring-1 ring-blue-100 dark:bg-blue-900/20 dark:text-blue-300 dark:ring-blue-900/30';
  }

  getProjectRetrainingConfig(project: MlProjectResponse): RetrainingConfigResponse | null {
    return this.retrainingConfigs[project.id] ?? null;
  }

  isRetrainingEnabled(project: MlProjectResponse): boolean {
    return !!this.getProjectRetrainingConfig(project)?.enabled;
  }

  getRetrainingBadgeLabel(project: MlProjectResponse): string {
    return 'Monitoring actif';
  }

  getRetrainingBadgeClass(project: MlProjectResponse): string {
    return this.isRetrainingEnabled(project)
      ? 'bg-green-50 text-green-700 ring-1 ring-green-100 dark:bg-green-900/20 dark:text-green-300 dark:ring-green-900/30'
      : 'bg-gray-50 text-gray-600 ring-1 ring-gray-100 dark:bg-gray-900/40 dark:text-gray-400 dark:ring-gray-800';
  }


  getRetrainingSectionTitle(
    config: RetrainingConfigResponse | null
  ): string {
    if (!config || !config.enabled) {
      return 'Monitoring non configuré';
    }
    return 'Monitoring du modèle actif';
  }

  getRetrainingNextDateHint(
    config: RetrainingConfigResponse | null
  ): string {
    if (!config || !config.enabled) {
      return 'Monitoring non configuré';
    }
    if (config.lastMonitoringCheckAt) {
      return 'Dernière analyse : ' + this.formatDateTime(config.lastMonitoringCheckAt);
    }
    return 'Monitoring actif';
  }

  private normalizeRetrainingConfig(
    config: RetrainingConfigResponse
  ): RetrainingConfigResponse {
    return {
      ...config,
      strategy: config.strategy || 'MONITORING_SMART'
    };
  }

  getMonitoringSummary(
    config: RetrainingConfigResponse | null
  ): string {
    if (!config || !config.enabled) {
      return 'Monitoring non configuré.';
    }
    return 'Performance et data drift surveillés automatiquement.';
  }

  isForceNowDisabled(project: MlProjectResponse): boolean {
    if (this.launchingProjectIds.has(project.id)) {
      return true;
    }

    const config = this.retrainingConfigs[project.id] ?? null;

    return (
      config?.running === true ||
      config?.manualTriggerRequested === true
    );
  }

  getRunningExperimentLabel(
    config: RetrainingConfigResponse | null
  ): string {
    if (!config) {
      return '-';
    }

    if (config.running && config.id != null) {
      return String(config.id);
    }

    return '-';
  }

  getRunningJobLabel(config: RetrainingConfigResponse | null): string {
    if (!config) {
      return '-';
    }

    const jobName = config.jobName;
    if (jobName) {
      return jobName;
    }

    const k8sName = config.kubeflowRunName;
    if (k8sName) {
      return k8sName;
    }

    return '-';
  }

  getProjectTypeLabel(projectType: ProjectType | string | null | undefined): string {
    return projectType === 'regression' ? 'Régression' : 'Classification';
  }

  formatMetric(metric: string | null | undefined): string {
    if (!metric) {
      return '-';
    }

    const labels: Record<string, string> = {
      accuracy: 'Accuracy',
      precision: 'Precision',
      precision_weighted: 'Precision weighted',
      recall: 'Recall',
      recall_weighted: 'Recall weighted',
      f1: 'F1',
      f1_score: 'F1 score',
      f1_weighted: 'F1 weighted',
      roc_auc: 'ROC AUC',
      log_loss: 'Log loss',
      average_precision: 'Average precision',
      cohen_kappa: 'Cohen kappa',
      mae: 'MAE',
      rmse: 'RMSE',
      mse: 'MSE',
      mape: 'MAPE',
      explained_variance: 'Explained variance',
      r2: 'R²',
      loss: 'Loss',
      val_loss: 'Validation loss',
      val_accuracy: 'Validation accuracy'
    };

    return labels[metric] || metric;
  }

  formatDate(value: string | null | undefined): string {
    if (!value || value === '-') {
      return '-';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    });
  }

  formatDateTime(value: string | null | undefined): string {
    if (!value || value === '-') {
      return '-';
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return date.toLocaleString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  private getBackendErrorMessage(error: any, fallback: string): string {
    if (error?.error?.message) {
      return error.error.message;
    }

    if (error?.error?.error) {
      return error.error.error;
    }

    if (typeof error?.error === 'string') {
      return error.error;
    }

    if (error?.message) {
      return error.message;
    }

    return fallback;
  }
}