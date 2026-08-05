import { Component, OnInit, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';

import {
  MlProjectRequest,
  MlProjectResponse,
  MlProjectMetricsRequest,
  ProjectService,
  ProjectType,
  RuntimeMode
} from '../../core/services/project.service';

import { PageBreadcrumbComponent } from '../../shared/components/common/page-breadcrumb/page-breadcrumb.component';

import {
  RetrainingConfigRequest,
  RetrainingConfigResponse,
  RetrainingFrequencyType,
  RetrainingService,
  RetrainingStrategy,
  ManualRetrainingResponse,
  ProjectRetrainingStatusResponse,
  isRetrainingNonTerminalStatus
} from '../../core/services/retraining.service';

type DataType = 'TABULAR' | 'TEXT' | 'IMAGE';

type ProjectFormModel = MlProjectRequest & {
  dataType: DataType;
  retrainingEnabled: boolean;
  retrainingFrequencyType: RetrainingFrequencyType;
  retrainingFrequencyValue: number;
  retrainingStrategy: RetrainingStrategy;
  failureMonitoringEnabled: boolean;
  performanceMonitoringEnabled: boolean;
  dataDriftMonitoringEnabled: boolean;
  monitoringLookbackHours: number;
  minPredictionsForMonitoring: number;
  maxPredictionErrorRatePercent: number;
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
    PageBreadcrumbComponent
  ],
  templateUrl: './my-projects.component.html',
  styles: ``
})
export class MyProjectsComponent implements OnInit {
  @ViewChild('projectFormSection') projectFormSection?: ElementRef<HTMLElement>;

  projects: MlProjectResponse[] = [];

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

  projectForm: ProjectFormModel = this.getDefaultProjectForm();

  constructor(
    private projectService: ProjectService,
    private retrainingService: RetrainingService
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
            nextRetrainingAt: status?.nextRetrainingAt ?? config.nextRetrainingAt,
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

      retrainingEnabled: false,
      retrainingFrequencyType: 'WEEK',
      retrainingFrequencyValue: 1,
      retrainingStrategy: 'SCHEDULED_ONLY',
      failureMonitoringEnabled: false,
      performanceMonitoringEnabled: false,
      dataDriftMonitoringEnabled: false,
      monitoringLookbackHours: 24,
      minPredictionsForMonitoring: 5,
      maxPredictionErrorRatePercent: 20,
      performanceDropThresholdPercent: 10,
      dataDriftThresholdPercent: 25
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
    this.loading = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.projectService.getMyProjects().subscribe({
      next: (response: MlProjectResponse[]) => {
        this.projects = (response || []).map((project) => this.normalizeProject(project));
        this.loading = false;

        this.projects.forEach((project) => {
          this.loadRetrainingConfigForProjectCard(project.id);
        });
      },
      error: (error) => {
        console.error('Load projects error:', error);
        this.loading = false;
        this.errorMessage = this.getBackendErrorMessage(
          error,
          'Impossible de charger vos projets.'
        );
      }
    });
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

      retrainingEnabled: false,
      retrainingFrequencyType: 'WEEK',
      retrainingFrequencyValue: 1,
      retrainingStrategy: 'SCHEDULED_ONLY',
      failureMonitoringEnabled: false,
      performanceMonitoringEnabled: false,
      dataDriftMonitoringEnabled: false,
      monitoringLookbackHours: 24,
      minPredictionsForMonitoring: 5,
      maxPredictionErrorRatePercent: 20,
      performanceDropThresholdPercent: 10,
      dataDriftThresholdPercent: 25
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

        const strategy =
          (config.strategy || 'SCHEDULED_ONLY') as RetrainingStrategy;

        this.projectForm.retrainingEnabled = config.enabled ?? false;
        this.projectForm.retrainingFrequencyType =
          config.frequencyType || 'WEEK';
        this.projectForm.retrainingFrequencyValue =
          config.frequencyValue || 1;
        this.projectForm.retrainingStrategy = strategy;

        this.projectForm.failureMonitoringEnabled =
          config.failureMonitoringEnabled ?? false;
        this.projectForm.performanceMonitoringEnabled =
          config.performanceMonitoringEnabled ?? false;
        this.projectForm.dataDriftMonitoringEnabled =
          config.dataDriftMonitoringEnabled ?? false;

        this.projectForm.monitoringLookbackHours =
          config.monitoringLookbackHours || 24;
        this.projectForm.minPredictionsForMonitoring =
          config.minPredictionsForMonitoring || 5;
        this.projectForm.maxPredictionErrorRatePercent =
          config.maxPredictionErrorRatePercent ?? 20;
        this.projectForm.performanceDropThresholdPercent =
          config.performanceDropThresholdPercent ?? 10;
        this.projectForm.dataDriftThresholdPercent =
          config.dataDriftThresholdPercent ?? 25;
      },
      error: () => {
        this.retrainingConfigs[projectId] = null;

        this.projectForm.retrainingEnabled = false;
        this.projectForm.retrainingFrequencyType = 'WEEK';
        this.projectForm.retrainingFrequencyValue = 1;
        this.projectForm.retrainingStrategy = 'SCHEDULED_ONLY';

        this.projectForm.failureMonitoringEnabled = false;
        this.projectForm.performanceMonitoringEnabled = false;
        this.projectForm.dataDriftMonitoringEnabled = false;

        this.projectForm.monitoringLookbackHours = 24;
        this.projectForm.minPredictionsForMonitoring = 5;
        this.projectForm.maxPredictionErrorRatePercent = 20;
        this.projectForm.performanceDropThresholdPercent = 10;
        this.projectForm.dataDriftThresholdPercent = 25;
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

  onRetrainingEnabledChange(enabled: boolean): void {
    this.projectForm.retrainingEnabled = enabled;

    if (enabled) {
      if (!this.projectForm.retrainingFrequencyType) {
        this.projectForm.retrainingFrequencyType = 'WEEK';
      }

      if (!this.projectForm.retrainingFrequencyValue || this.projectForm.retrainingFrequencyValue <= 0) {
        this.projectForm.retrainingFrequencyValue = 1;
      }

      if (!this.projectForm.retrainingStrategy) {
        this.projectForm.retrainingStrategy = 'SCHEDULED_ONLY';
      }

      this.projectForm.monitoringLookbackHours = this.projectForm.monitoringLookbackHours || 24;
      this.projectForm.minPredictionsForMonitoring = this.projectForm.minPredictionsForMonitoring || 5;
      this.projectForm.maxPredictionErrorRatePercent = this.projectForm.maxPredictionErrorRatePercent ?? 20;
      this.projectForm.performanceDropThresholdPercent = this.projectForm.performanceDropThresholdPercent ?? 10;
      this.projectForm.dataDriftThresholdPercent = this.projectForm.dataDriftThresholdPercent ?? 25;
    }
  }

  onRetrainingStrategyChange(
    strategy: RetrainingStrategy
  ): void {
    this.projectForm.retrainingStrategy =
      strategy || 'SCHEDULED_ONLY';

    if (!this.strategyUsesMonitoring(strategy)) {
      this.projectForm.failureMonitoringEnabled = false;
      this.projectForm.performanceMonitoringEnabled = false;
      this.projectForm.dataDriftMonitoringEnabled = false;
      return;
    }

    /*
     * Lors du passage vers une stratégie avec monitoring,
     * activer les trois contrôles par défaut uniquement si aucun
     * contrôle n'est déjà sélectionné.
     */
    const hasAtLeastOneMonitoringCheck =
      this.projectForm.failureMonitoringEnabled ||
      this.projectForm.performanceMonitoringEnabled ||
      this.projectForm.dataDriftMonitoringEnabled;

    if (!hasAtLeastOneMonitoringCheck) {
      this.projectForm.failureMonitoringEnabled = true;
      this.projectForm.performanceMonitoringEnabled = true;
      this.projectForm.dataDriftMonitoringEnabled = true;
    }
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
    if (!this.projectForm.retrainingEnabled) {
      return true;
    }

    if (!this.projectForm.retrainingFrequencyType) {
      this.errorMessage = 'Veuillez choisir la fréquence de réentraînement.';
      return false;
    }

    if (
      !this.projectForm.retrainingFrequencyValue ||
      this.projectForm.retrainingFrequencyValue <= 0
    ) {
      this.errorMessage = 'La valeur de fréquence doit être supérieure à 0.';
      return false;
    }

    if (!this.projectForm.retrainingStrategy) {
      this.errorMessage = 'Veuillez choisir une stratégie de réentraînement.';
      return false;
    }

    if (this.strategyUsesMonitoring(this.projectForm.retrainingStrategy)) {
      if (
        !this.projectForm.failureMonitoringEnabled &&
        !this.projectForm.performanceMonitoringEnabled &&
        !this.projectForm.dataDriftMonitoringEnabled
      ) {
        this.errorMessage =
          'Activez au moins un contrôle de monitoring.';
        return false;
      }

      if (
        !this.projectForm.monitoringLookbackHours ||
        this.projectForm.monitoringLookbackHours <= 0
      ) {
        this.errorMessage =
          'La fenêtre de monitoring doit être supérieure à 0 heure.';
        return false;
      }

      if (
        !this.projectForm.minPredictionsForMonitoring ||
        this.projectForm.minPredictionsForMonitoring <= 0
      ) {
        this.errorMessage =
          'Le nombre minimum de prédictions doit être supérieur à 0.';
        return false;
      }

      if (
        this.projectForm.maxPredictionErrorRatePercent < 0 ||
        this.projectForm.maxPredictionErrorRatePercent > 100
      ) {
        this.errorMessage =
          'Le seuil du taux d’erreur doit être entre 0 et 100 %.';
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
                  this.loadProjects();
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
            this.successMessage = this.projectForm.retrainingEnabled
              ? 'Projet créé avec succès. Le réentraînement automatique est configuré.'
              : 'Projet créé avec succès.';

            this.projects = [normalizedCreatedProject, ...this.projects];
            this.closeForm();
            this.loadProjects();
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
    const strategy =
      this.projectForm.retrainingStrategy || 'SCHEDULED_ONLY';

    const monitoringEnabled =
      this.projectForm.retrainingEnabled &&
      this.strategyUsesMonitoring(strategy);

    const request: RetrainingConfigRequest = {
      enabled: this.projectForm.retrainingEnabled,
      frequencyType:
        this.projectForm.retrainingFrequencyType || 'WEEK',
      frequencyValue:
        this.projectForm.retrainingFrequencyValue || 1,
      strategy,

      /*
       * SCHEDULED_ONLY doit toujours être sauvegardé avec les
       * trois contrôles intelligents désactivés.
       */
      failureMonitoringEnabled:
        monitoringEnabled &&
        this.projectForm.failureMonitoringEnabled,
      performanceMonitoringEnabled:
        monitoringEnabled &&
        this.projectForm.performanceMonitoringEnabled,
      dataDriftMonitoringEnabled:
        monitoringEnabled &&
        this.projectForm.dataDriftMonitoringEnabled,

      monitoringLookbackHours:
        this.projectForm.monitoringLookbackHours || 24,
      minPredictionsForMonitoring:
        this.projectForm.minPredictionsForMonitoring || 5,
      maxPredictionErrorRatePercent:
        this.projectForm.maxPredictionErrorRatePercent ?? 20,
      performanceDropThresholdPercent:
        this.projectForm.performanceDropThresholdPercent ?? 10,
      dataDriftThresholdPercent:
        this.projectForm.dataDriftThresholdPercent ?? 25
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
        'Activez d’abord le réentraînement automatique pour ce projet.';
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
    const config = this.getProjectRetrainingConfig(project);

    if (!config || !config.enabled) {
      return 'Réentraînement désactivé';
    }

    return `Réentraînement : chaque ${config.frequencyValue || 1} ${this.getFrequencyUnitLabel(
      config.frequencyType,
      config.frequencyValue || 1
    )}`;
  }

  getRetrainingBadgeClass(project: MlProjectResponse): string {
    return this.isRetrainingEnabled(project)
      ? 'bg-green-50 text-green-700 ring-1 ring-green-100 dark:bg-green-900/20 dark:text-green-300 dark:ring-green-900/30'
      : 'bg-gray-50 text-gray-600 ring-1 ring-gray-100 dark:bg-gray-900/40 dark:text-gray-400 dark:ring-gray-800';
  }


  getRetrainingStrategyLabel(strategy: RetrainingStrategy | string | null | undefined): string {
    const value = String(strategy || 'SCHEDULED_ONLY').toUpperCase();

    if (value === 'SCHEDULED_ONLY') {
      return 'Planifié uniquement';
    }

    if (value === 'SCHEDULED_WITH_MONITORING') {
      return 'Planifié + monitoring';
    }

    if (value === 'SMART_MONITORING_ONLY') {
      return 'Monitoring intelligent';
    }

    return 'Full smart';
  }

  getRetrainingStrategyDescription(strategy: RetrainingStrategy | string | null | undefined): string {
    const value = String(strategy || 'SCHEDULED_ONLY').toUpperCase();

    if (value === 'SCHEDULED_ONLY') {
      return 'La plateforme relance le training à chaque échéance, sans vérifier les signaux de monitoring.';
    }

    if (value === 'SCHEDULED_WITH_MONITORING') {
      return 'La plateforme vérifie les erreurs, la performance et le drift avant de relancer.';
    }

    if (value === 'SMART_MONITORING_ONLY') {
      return 'La plateforme relance seulement si un signal de monitoring justifie un nouveau training.';
    }

    return 'Mode complet : fréquence + erreurs récentes + performance + data drift avant décision.';
  }

  getRetrainingSectionTitle(
    config: RetrainingConfigResponse | null
  ): string {
    if (!config || !config.enabled) {
      return 'Réentraînement désactivé';
    }

    const strategy = String(
      config.strategy || 'SCHEDULED_ONLY'
    ).toUpperCase();

    if (strategy === 'SCHEDULED_ONLY') {
      return 'Réentraînement planifié';
    }

    if (strategy === 'SCHEDULED_WITH_MONITORING') {
      return 'Réentraînement planifié avec monitoring';
    }

    if (strategy === 'SMART_MONITORING_ONLY') {
      return 'Réentraînement intelligent';
    }

    return 'Réentraînement intelligent complet';
  }

  strategyUsesMonitoring(
    strategy: RetrainingStrategy | string | null | undefined
  ): boolean {
    return String(
      strategy || 'SCHEDULED_ONLY'
    ).toUpperCase() !== 'SCHEDULED_ONLY';
  }

  shouldShowMonitoringControls(
    config: RetrainingConfigResponse | null
  ): boolean {
    return !!config?.enabled &&
      this.strategyUsesMonitoring(config.strategy);
  }

  private normalizeRetrainingConfig(
    config: RetrainingConfigResponse
  ): RetrainingConfigResponse {
    const strategy =
      (config.strategy || 'SCHEDULED_ONLY') as RetrainingStrategy;

    const monitoringEnabled =
      config.enabled === true &&
      this.strategyUsesMonitoring(strategy);

    return {
      ...config,
      strategy,
      failureMonitoringEnabled:
        monitoringEnabled &&
        config.failureMonitoringEnabled === true,
      performanceMonitoringEnabled:
        monitoringEnabled &&
        config.performanceMonitoringEnabled === true,
      dataDriftMonitoringEnabled:
        monitoringEnabled &&
        config.dataDriftMonitoringEnabled === true
    };
  }

  getMonitoringSummary(
    config: RetrainingConfigResponse | null
  ): string {
    if (!config || !config.enabled) {
      return 'Monitoring inactif tant que le réentraînement est désactivé.';
    }

    if (!this.strategyUsesMonitoring(config.strategy)) {
      return 'Aucun contrôle intelligent actif. Le réentraînement est lancé uniquement selon la planification.';
    }

    const activeChecks: string[] = [];

    if (config.failureMonitoringEnabled) {
      activeChecks.push('échecs');
    }

    if (config.performanceMonitoringEnabled) {
      activeChecks.push('performance');
    }

    if (config.dataDriftMonitoringEnabled) {
      activeChecks.push('data drift');
    }

    return activeChecks.length > 0
      ? 'Contrôles actifs : ' + activeChecks.join(', ')
      : 'Aucun contrôle intelligent activé.';
  }

  getRetrainingDecisionLabel(config: RetrainingConfigResponse | null): string {
    if (!config) {
      return '-';
    }

    return config.lastDecisionReason || config.lastTriggerReason || 'Aucune décision récente.';
  }

  isForceNowDisabled(project: MlProjectResponse): boolean {
    if (this.launchingProjectIds.has(project.id)) {
      return true;
    }

    const config = this.retrainingConfigs[project.id] ?? null;

    return (
      !config?.enabled ||
      config.running === true ||
      config.manualTriggerRequested === true
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

  getMonitoringFlagLabel(enabled: boolean | null | undefined): string {
    return enabled ? 'Activé' : 'Désactivé';
  }

  getMonitoringFlagClass(enabled: boolean | null | undefined): string {
    return enabled
      ? 'bg-green-50 text-green-700 ring-1 ring-green-100 dark:bg-green-900/20 dark:text-green-300 dark:ring-green-900/30'
      : 'bg-gray-50 text-gray-600 ring-1 ring-gray-100 dark:bg-gray-900/40 dark:text-gray-400 dark:ring-gray-800';
  }

  getFrequencyUnitLabel(
    frequencyType: RetrainingFrequencyType | string | null | undefined,
    value: number = 1
  ): string {
    const type = String(frequencyType || 'WEEK').toUpperCase();

    if (type === 'DAY') {
      return value > 1 ? 'jours' : 'jour';
    }

    if (type === 'MONTH') {
      return 'mois';
    }

    return value > 1 ? 'semaines' : 'semaine';
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