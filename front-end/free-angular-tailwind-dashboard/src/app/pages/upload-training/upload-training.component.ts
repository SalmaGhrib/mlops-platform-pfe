import { CommonModule, KeyValuePipe } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import {
  AlgorithmReplacementPayload,
  AnalyzeModelFileResponse,
  ExperimentService,
  MlExperimentResponse,
  MlProjectResponse,
  RuntimeAnalysisResult,
  UploadAndRunCustomModelRequest,
} from '../../core/services/experiment.service';

type ReplacementAction = 'KEEP' | 'REPLACE';
type ProjectType = 'classification' | 'regression';
type RuntimeMode = 'STANDARD_RUNTIME' | 'CUSTOM_DOCKERFILE';

interface ReplacementOption {
  module: string;
  className: string;
  display: string;
}

interface DetectedAlgorithm {
  id: string;
  occurrenceId?: string;
  varName: string | null;
  originalClass: string;
  originalDisplay: string;
  modelType: string;
  supported: boolean;
  module?: string;
  line?: number | null;
  column?: number | null;
  replacementOptions: ReplacementOption[];
}

interface AlgorithmSelection {
  action: ReplacementAction;
  selectedModule: string;
  selectedAlgorithm: string;
}

@Component({
  selector: 'app-upload-training',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, KeyValuePipe],
  templateUrl: './upload-training.component.html',
})
export class UploadTrainingComponent implements OnInit, OnDestroy {
  projects: MlProjectResponse[] = [];
  experiments: MlExperimentResponse[] = [];

  selectedProjectId: number | null = null;

  modelName = '';
  runName = '';

  modelFile: File | null = null;
  datasetFile: File | null = null;

  dockerfileFile: File | null = null;
  requirementsFile: File | null = null;
  customImageUri = '';

  loadingProjects = false;
  loadingExperiments = false;
  launching = false;
  analyzingModelFile = false;

  errorMessage = '';
  successMessage = '';

  analysisDone = false;
  analysisSummary = '';
  warnings: string[] = [];
  runtimeAnalysis: RuntimeAnalysisResult | null = null;

  detectedAlgorithms: DetectedAlgorithm[] = [];
  supportedModules: any[] = [];
  unsupportedModules: any[] = [];
  algorithmSelections: Record<string, AlgorithmSelection> = {};

  private pollingIntervalId: any = null;

  constructor(private experimentService: ExperimentService) {}

  ngOnInit(): void {
    this.loadProjects();
  }

  ngOnDestroy(): void {
    this.stopPollingExperiments();
  }

  loadProjects(): void {
    this.loadingProjects = true;
    this.errorMessage = '';

    this.experimentService.getProjects().subscribe({
      next: (projects: MlProjectResponse[]) => {
        this.projects = (projects || []).map((project) => this.normalizeProject(project));
        this.loadingProjects = false;

        const queryParams = new URLSearchParams(window.location.search);
        const projectIdParam = queryParams.get('projectId');

        if (projectIdParam) {
          const projectId = Number(projectIdParam);
          const exists = this.projects.some((project) => project.id === projectId);

          if (exists) {
            this.selectedProjectId = projectId;
            this.onProjectChange();
          }
        }
      },
      error: (error) => {
        this.loadingProjects = false;
        this.errorMessage = this.extractErrorMessage(
          error,
          'Erreur lors du chargement des projets.'
        );
      },
    });
  }

  private normalizeProject(project: MlProjectResponse): MlProjectResponse {
    return {
      ...project,
      runtimeMode: project.runtimeMode || 'STANDARD_RUNTIME',
      customImageUri: project.customImageUri ?? null,
      dockerfilePath: project.dockerfilePath ?? null,
      requirementsPath: project.requirementsPath ?? null,
    };
  }

  onProjectChange(): void {
    this.errorMessage = '';
    this.successMessage = '';
    this.resetFilesOnly();

    const selectedProject = this.getSelectedProject();

    if (!selectedProject) {
      this.stopPollingExperiments();
      this.experiments = [];
      return;
    }

    this.modelName = `${this.slugify(selectedProject.name)}_model`;
    this.runName = `${this.slugify(selectedProject.name)}_run_${this.getDateSuffix()}`;
    this.customImageUri = selectedProject.customImageUri || '';

    this.loadExperimentsForSelectedProject();
    this.startPollingExperiments();
  }

  getSelectedProject(): MlProjectResponse | null {
    if (this.selectedProjectId === null) {
      return null;
    }

    return this.projects.find((project) => project.id === this.selectedProjectId) || null;
  }

  getSelectedProjectType(): ProjectType {
    const project = this.getSelectedProject();
    const rawType = String(project?.projectType || 'classification').toLowerCase();

    return rawType.includes('regression') ? 'regression' : 'classification';
  }

  getSelectedRuntimeMode(): RuntimeMode {
    const project = this.getSelectedProject();
    const value = String(project?.runtimeMode || 'STANDARD_RUNTIME').toUpperCase();

    return value === 'CUSTOM_DOCKERFILE' ? 'CUSTOM_DOCKERFILE' : 'STANDARD_RUNTIME';
  }

  isCustomDockerfileMode(): boolean {
    return this.getSelectedRuntimeMode() === 'CUSTOM_DOCKERFILE';
  }

  isStandardRuntimeMode(): boolean {
    return this.getSelectedRuntimeMode() === 'STANDARD_RUNTIME';
  }

  getRuntimeModeLabel(): string {
    return this.isCustomDockerfileMode() ? 'Dockerfile personnalisé' : 'Runtime standard';
  }

  getRuntimeModeDescription(): string {
    if (this.isCustomDockerfileMode()) {
      return 'Ce projet utilise un environnement Docker personnalisé. Le remplacement automatique d’algorithmes est désactivé dans ce mode.';
    }

    return 'Ce projet utilise le runtime standard de la plateforme. Le script sera analysé et exécuté tel quel s’il est compatible avec l’image standard.';
  }

  canLaunchPipeline(): boolean {
    if (this.isCustomDockerfileMode()) {
      return true;
    }

    return this.runtimeAnalysis?.runtimeCompatible === true;
  }

  formatModuleName(module: any): string {
    if (!module) {
      return '';
    }

    if (typeof module === 'string') {
      return module;
    }

    return String(module.name || module.module || module.importName || module.packageName || module.key || module.label || '');
  }

  formatLibraryName(library: any): string {
    if (!library) {
      return '';
    }

    if (typeof library === 'string') {
      return library;
    }

    return String(library.name || library.module || library.key || library.label || '');
  }

  isModuleSupported(module: any): boolean {
    if (!module) {
      return false;
    }

    if (typeof module === 'string') {
      return true;
    }

    return module.supported === true || module.isSupported === true;
  }

  formatUnsupportedModules(): string {
    const modules = this.runtimeAnalysis?.unsupportedModules || [];

  return (modules as any[])
    .map((module) => this.formatModuleName(module))
    .filter((name) => !!name)
    .join(', ');
}

hasDetectedImports(): boolean {
  return Array.isArray(this.runtimeAnalysis?.detectedImports)
    && this.runtimeAnalysis!.detectedImports.length > 0;
}

hasDetectedLibraries(): boolean {
    return Array.isArray(this.runtimeAnalysis?.detectedLibraries)
      && this.runtimeAnalysis!.detectedLibraries.length > 0;
  }

  hasDetectedMetrics(): boolean {
    return Array.isArray(this.runtimeAnalysis?.detectedMetrics)
      && this.runtimeAnalysis!.detectedMetrics.length > 0;
  }

  hasMissingDependencies(): boolean {
    return Array.isArray(this.runtimeAnalysis?.missingDependencies)
      && this.runtimeAnalysis!.missingDependencies.length > 0;
  }

  hasDetectedModules(): boolean {
  return Array.isArray(this.runtimeAnalysis?.detectedModules)
    && this.runtimeAnalysis!.detectedModules.length > 0;
}

hasDetectedAlgorithms(): boolean {
  return Array.isArray(this.runtimeAnalysis?.detectedAlgorithms)
    && this.runtimeAnalysis!.detectedAlgorithms.length > 0;
}

hasUnsupportedModules(): boolean {
  return Array.isArray(this.runtimeAnalysis?.unsupportedModules)
    && this.runtimeAnalysis!.unsupportedModules.length > 0;
}

hasAnalysisWarnings(): boolean {
  return Array.isArray(this.runtimeAnalysis?.warnings)
    && this.runtimeAnalysis!.warnings.length > 0;
}

getDetectedModulesCount(): number {
  return this.runtimeAnalysis?.detectedModules?.length || 0;
}

getDetectedComponentsCount(): number {
  return this.runtimeAnalysis?.detectedAlgorithms?.length || 0;
}

  loadExperimentsForSelectedProject(): void {
    if (this.selectedProjectId === null) {
      this.experiments = [];
      return;
    }

    this.loadingExperiments = true;

    this.experimentService.getExperimentsByProject(this.selectedProjectId).subscribe({
      next: (experiments: MlExperimentResponse[]) => {
        this.experiments = this.normalizeExperiments(experiments || []);
        this.loadingExperiments = false;
      },
      error: (error) => {
        this.loadingExperiments = false;
        this.errorMessage = this.extractErrorMessage(
          error,
          'Erreur lors du chargement des expériences.'
        );
      },
    });
  }

  syncExperimentsForSelectedProject(): void {
    if (this.selectedProjectId === null) {
      return;
    }

    this.experimentService.syncProjectResults(this.selectedProjectId).subscribe({
      next: (experiments: MlExperimentResponse[]) => {
        this.experiments = this.normalizeExperiments(experiments || []);
        this.successMessage = 'Synchronisation MLflow terminée.';
      },
      error: (error) => {
        this.errorMessage = this.extractErrorMessage(
          error,
          'Erreur lors de la synchronisation MLflow.'
        );
        this.loadExperimentsForSelectedProject();
      },
    });
  }

  startPollingExperiments(): void {
    this.stopPollingExperiments();

    if (this.selectedProjectId === null) {
      return;
    }

    this.pollingIntervalId = setInterval(() => {
      if (this.selectedProjectId === null) {
        this.stopPollingExperiments();
        return;
      }

      this.experimentService.getExperimentsByProject(this.selectedProjectId).subscribe({
        next: (experiments: MlExperimentResponse[]) => {
          this.experiments = this.normalizeExperiments(experiments || []);

          const hasRunningExperiment = this.experiments.some((experiment) =>
            ['CREATED', 'RUNNING', 'PENDING', 'BUILDING_IMAGE', 'IMAGE_READY'].includes(
              String(experiment.status || '').toUpperCase()
            )
          );

          if (!hasRunningExperiment) {
            this.stopPollingExperiments();
            this.syncExperimentsForSelectedProject();
          }
        },
        error: () => {
          this.stopPollingExperiments();
          this.loadExperimentsForSelectedProject();
        },
      });
    }, 10000);
  }

  stopPollingExperiments(): void {
    if (this.pollingIntervalId) {
      clearInterval(this.pollingIntervalId);
      this.pollingIntervalId = null;
    }
  }

  onModelFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files && input.files.length > 0 ? input.files[0] : null;

    this.modelFile = file;
    this.resetAnalysisState();

    if (!file) {
      return;
    }

    if (this.isCustomDockerfileMode()) {
      this.analysisDone = false;
      this.analysisSummary = 'Analyse désactivée en mode Dockerfile personnalisé.';
      return;
    }

    this.analyzeRuntimeScript(file);
  }

  onDatasetFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.datasetFile = input.files && input.files.length > 0 ? input.files[0] : null;
  }

  onDockerfileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.dockerfileFile = input.files && input.files.length > 0 ? input.files[0] : null;
  }

  onRequirementsFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.requirementsFile = input.files && input.files.length > 0 ? input.files[0] : null;
  }

  private resetAnalysisState(): void {
    this.analysisDone = false;
    this.analysisSummary = '';
    this.warnings = [];
    this.runtimeAnalysis = null;
    this.detectedAlgorithms = [];
    this.supportedModules = [];
    this.unsupportedModules = [];
    this.algorithmSelections = {};
  }

  analyzeRuntimeScript(file: File): void {
    this.analyzingModelFile = true;
    this.errorMessage = '';
    this.successMessage = '';
    this.resetAnalysisState();

    this.experimentService.analyzeRuntimeScript(file).subscribe({
      next: (response: any) => {
        console.log('Runtime analysis response:', response);

        const mappedResponse = this.mapRuntimeAnalysisResponse(response || {});

        this.runtimeAnalysis = mappedResponse;
        this.analysisSummary = mappedResponse.summary || mappedResponse.message || '';
        this.warnings = mappedResponse.warnings || [];

        this.analyzingModelFile = false;
        this.analysisDone = true;

        console.log('Mapped runtime analysis:', this.runtimeAnalysis);
      },

      error: (error) => {
        console.error('Runtime analysis failed:', error);

        /*
         * Important pour AKS :
         * si le service d’analyse runtime est indisponible,
         * on ne bloque pas le training STANDARD_RUNTIME.
         */
        this.setRuntimeAnalysisFallback(
          'Analyse runtime indisponible sur AKS. Le lancement STANDARD_RUNTIME est autorisé.'
        );
      },
    });
  }

  private mapRuntimeAnalysisResponse(response: any): RuntimeAnalysisResult {
    const toStringArray = (value: any): string[] => {
      if (!value) {
        return [];
      }

      if (Array.isArray(value)) {
        return value
          .map((item) => {
            if (typeof item === 'string') {
              return item;
            }

            return String(
              item?.name ||
              item?.module ||
              item?.importName ||
              item?.packageName ||
              item?.key ||
              item?.label ||
              ''
            );
          })
          .map((item) => item.trim())
          .filter((item) => item.length > 0);
      }

      if (typeof value === 'string') {
        return value
          .split(',')
          .map((item) => item.trim())
          .filter((item) => item.length > 0);
      }

      return [];
    };

    const unique = (items: string[]): string[] => {
      return items.filter((item, index, array) => item && array.indexOf(item) === index);
    };

    const detectedImports: string[] = unique([
      ...toStringArray(response.detectedImports),
      ...toStringArray(response.detected_imports),
      ...toStringArray(response.detectedModules),
      ...toStringArray(response.detected_modules),
    ]);

    const supportedImports: string[] = unique([
      ...toStringArray(response.supportedImports),
      ...toStringArray(response.supported_imports),
      ...toStringArray(response.supportedModules),
      ...toStringArray(response.supported_modules),
    ]);

    const unsupportedImports: string[] = unique([
      ...toStringArray(response.unsupportedImports),
      ...toStringArray(response.unsupported_imports),
      ...toStringArray(response.unsupportedModules),
      ...toStringArray(response.unsupported_modules),
    ]);

    const runtimeCompatible =
      response.runtimeCompatible !== undefined
        ? response.runtimeCompatible === true
        : response.runtime_compatible !== undefined
          ? response.runtime_compatible === true
          : response.compatible !== undefined
            ? response.compatible === true
            : response.supported !== undefined
              ? response.supported === true
              : unsupportedImports.length === 0;

    const detectedModules = detectedImports.map((moduleName: string) => {
      const isUnsupported = unsupportedImports.includes(moduleName);
      const isSupported =
        supportedImports.includes(moduleName) ||
        runtimeCompatible ||
        response.supported === true ||
        response.compatible === true;

      return {
        name: moduleName,
        supported: !isUnsupported && isSupported,
        source: !isUnsupported && isSupported ? 'standard_runtime' : 'missing',
        message: !isUnsupported && isSupported
          ? 'Module disponible dans le Runtime standard.'
          : 'Module absent de l’image standard. Utilisez le mode Dockerfile personnalisé.',
      };
    });

    const unsupportedModules = unsupportedImports.map((moduleName: string) => ({
      name: moduleName,
      supported: false,
      source: 'missing',
      message: 'Module absent de l’image standard. Utilisez le mode Dockerfile personnalisé.',
    }));

    const detectedLibraries = unique([
      ...toStringArray(response.detectedLibraries),
      ...toStringArray(response.detected_libraries),
      ...toStringArray(response.supportedLibraries),
      ...toStringArray(response.supported_libraries),
      ...toStringArray(response.unsupportedLibraries),
      ...toStringArray(response.unsupported_libraries),
    ]);

    const missingDependencies = unique([
      ...toStringArray(response.missingDependencies),
      ...toStringArray(response.missing_dependencies),
      ...unsupportedImports,
    ]);

    const rawDetectedAlgorithms =
      response.detectedAlgorithms ||
      response.detected_algorithms ||
      response.detectedComponents ||
      response.detected_components ||
      [];

    const detectedAlgorithms = Array.isArray(rawDetectedAlgorithms)
      ? rawDetectedAlgorithms.map((item: any) => {
          if (typeof item === 'string') {
            return {
              name: item,
              category: 'ML',
              supported: runtimeCompatible,
              replaceable: false,
              message: runtimeCompatible
                ? 'Composant détecté et accepté.'
                : 'Composant détecté mais non validé par le runtime standard.',
            };
          }

          return {
            name: String(item?.name || item?.className || item?.originalClass || 'Composant détecté'),
            category: item?.category || item?.modelType || 'ML',
            supported: item?.supported !== undefined ? item.supported === true : runtimeCompatible,
            replaceable: item?.replaceable !== undefined ? item.replaceable === true : false,
            message: item?.message || '',
            module: item?.module,
            variable: item?.variable || item?.varName,
            line: item?.line,
          };
        })
      : [];

    const detectedMetrics = unique([
      ...toStringArray(response.detectedMetrics),
      ...toStringArray(response.detected_metrics),
    ]);

    const warnings = unique([
      ...toStringArray(response.warnings),
    ]);

    return {
      runtimeCompatible,
      compatible: runtimeCompatible,
      runtime_compatible: runtimeCompatible,
      status: response.status || (runtimeCompatible ? 'COMPATIBLE' : 'INCOMPATIBLE'),
      message:
        response.message ||
        response.summary ||
        (runtimeCompatible
          ? 'Script compatible avec le Runtime standard.'
          : 'Script non compatible avec le Runtime standard.'),
      summary:
        response.summary ||
        response.message ||
        (runtimeCompatible
          ? 'Script compatible avec le Runtime standard.'
          : 'Script non compatible avec le Runtime standard.'),
      detectedImports,
      supportedImports,
      unsupportedImports,
      detectedMetrics,
      detectedModules,
      supportedModules: response.supportedModules || response.supported_modules || [],
      unsupportedModules,
      detectedLibraries,
      supportedLibraries: response.supportedLibraries || response.supported_libraries || [],
      unsupportedLibraries: response.unsupportedLibraries || response.unsupported_libraries || [],
      missingDependencies,
      detectedAlgorithms,
      replaceableAlgorithms: response.replaceableAlgorithms || response.replaceable_algorithms || [],
      warnings,
      recommendation:
        response.recommendation ||
        (runtimeCompatible
          ? 'Runtime standard compatible.'
          : 'Utilisez le mode Dockerfile personnalisé ou ajoutez les dépendances manquantes.'),
    };
  }

  private setRuntimeAnalysisFallback(message: string): void {
    const fallbackAnalysis: RuntimeAnalysisResult = {
      runtimeCompatible: true,
      compatible: true,
      runtime_compatible: true,
      status: 'COMPATIBLE',
      message,
      summary: message,
      detectedImports: [],
      supportedImports: [],
      unsupportedImports: [],
      detectedMetrics: [],
      detectedModules: [],
      supportedModules: [],
      unsupportedModules: [],
      detectedLibraries: [],
      supportedLibraries: [],
      unsupportedLibraries: [],
      missingDependencies: [],
      detectedAlgorithms: [],
      replaceableAlgorithms: [],
      warnings: [message],
      recommendation:
        'Analyse automatique indisponible. Le script sera lancé avec le Runtime standard.',
    };

    this.runtimeAnalysis = fallbackAnalysis;
    this.analysisSummary = message;
    this.warnings = fallbackAnalysis.warnings || [];

    this.analyzingModelFile = false;
    this.analysisDone = true;
  }

  analyzeModelFile(file: File): void {
    this.analyzingModelFile = true;
    this.errorMessage = '';
    this.successMessage = '';
    this.resetAnalysisState();

    this.experimentService.analyzeModelFile(file).subscribe({
      next: (response: AnalyzeModelFileResponse) => {
        const hasAlgorithms = this.responseContainsAlgorithms(response);

        if (hasAlgorithms) {
          this.analyzingModelFile = false;
          this.analysisDone = true;
          this.handleAnalysisResponse(response);
          return;
        }

        this.experimentService.analyzeCustomModelFile(file).subscribe({
          next: (fallbackResponse: AnalyzeModelFileResponse) => {
            this.analyzingModelFile = false;
            this.analysisDone = true;
            this.handleAnalysisResponse(fallbackResponse);
          },
          error: () => {
            this.analyzingModelFile = false;
            this.analysisDone = true;
            this.handleAnalysisResponse(response);
          },
        });
      },
      error: () => {
        this.experimentService.analyzeCustomModelFile(file).subscribe({
          next: (fallbackResponse: AnalyzeModelFileResponse) => {
            this.analyzingModelFile = false;
            this.analysisDone = true;
            this.handleAnalysisResponse(fallbackResponse);
          },
          error: (error) => {
            this.analyzingModelFile = false;
            this.analysisDone = true;
            this.errorMessage = this.extractErrorMessage(
              error,
              'Erreur lors de l’analyse du fichier modèle.'
            );
          },
        });
      },
    });
  }

  private responseContainsAlgorithms(response: any): boolean {
    return this.extractAlgorithmsFromAnalysisResponse(response).length > 0;
  }

  private extractAlgorithmsFromAnalysisResponse(response: any): any[] {
    const analysis = response?.analysis || response?.data || response?.result || response || {};

    const direct =
      analysis.detected_algorithms ||
      analysis.detectedAlgorithms ||
      analysis.algorithms ||
      analysis.replaceable_algorithms ||
      analysis.replaceableAlgorithms ||
      analysis.detected_models ||
      analysis.detectedModels ||
      analysis.model_occurrences ||
      analysis.modelOccurrences ||
      analysis.occurrences ||
      analysis.models ||
      analysis.used_algorithms ||
      analysis.usedAlgorithms ||
      response?.detected_algorithms ||
      response?.detectedAlgorithms ||
      response?.algorithms ||
      response?.used_algorithms ||
      response?.usedAlgorithms ||
      [];

    if (Array.isArray(direct) && direct.length > 0) {
      return direct;
    }

    return this.findFirstAlgorithmArrayDeep(response);
  }

  private findFirstAlgorithmArrayDeep(value: any): any[] {
    if (!value) {
      return [];
    }

    if (Array.isArray(value)) {
      const looksLikeAlgorithms = value.some((item) => {
        if (!item || typeof item !== 'object') {
          return false;
        }

        return !!(
          item.class ||
          item.className ||
          item.class_name ||
          item.original_class ||
          item.originalClass ||
          item.canonical_class ||
          item.canonicalClass ||
          item.algorithm ||
          item.display ||
          item.replacement_options ||
          item.replacementOptions ||
          item.available_replacements ||
          item.availableReplacements
        );
      });

      return looksLikeAlgorithms ? value : [];
    }

    if (typeof value !== 'object') {
      return [];
    }

    const priorityKeys = [
      'detected_algorithms',
      'detectedAlgorithms',
      'algorithms',
      'replaceable_algorithms',
      'replaceableAlgorithms',
      'used_algorithms',
      'usedAlgorithms',
      'model_occurrences',
      'modelOccurrences',
      'occurrences',
      'models',
    ];

    for (const key of priorityKeys) {
      const found = this.findFirstAlgorithmArrayDeep(value[key]);

      if (found.length > 0) {
        return found;
      }
    }

    for (const key of Object.keys(value)) {
      const found = this.findFirstAlgorithmArrayDeep(value[key]);

      if (found.length > 0) {
        return found;
      }
    }

    return [];
  }

  private handleAnalysisResponse(response: AnalyzeModelFileResponse): void {
    const analysis: any =
      (response as any).analysis ||
      (response as any).data ||
      (response as any).result ||
      response ||
      {};

    if ((response as any).error || analysis.error) {
      this.errorMessage = String((response as any).error || analysis.error);
    }

    this.warnings = analysis.warnings || (response as any).warnings || [];

    this.analysisSummary =
      analysis.summary ||
      analysis.message ||
      analysis.analysis_summary ||
      analysis.analysisSummary ||
      (response as any).summary ||
      (response as any).message ||
      '';

    let algorithms = this.extractAlgorithmsFromAnalysisResponse(response);

    if ((!algorithms || algorithms.length === 0) && this.analysisSummary) {
      algorithms = this.inferAlgorithmsFromSummary(this.analysisSummary);
    }

    if ((!algorithms || algorithms.length === 0) && (analysis.details || (response as any).details)) {
      algorithms = this.inferAlgorithmsFromSummary(
        analysis.details || (response as any).details || ''
      );
    }

    const selectedProjectType = this.getSelectedProjectType();

    const allDetectedAlgorithms: DetectedAlgorithm[] = (algorithms || []).map(
      (item: any, index: number) => {
        const rawName =
          item.original_class ||
          item.originalClass ||
          item.canonical_class ||
          item.canonicalClass ||
          item.class_name ||
          item.className ||
          item.class ||
          item.name ||
          item.algorithm ||
          item.detected_class ||
          item.detectedClass ||
          item.full_name ||
          item.fullName ||
          item.display ||
          item.label ||
          item.originalDisplay ||
          item.original_display ||
          '';

        const originalClass =
          this.normalizeFriendlyAlgorithmName(rawName, selectedProjectType) ||
          this.normalizeDetectedAlgorithmName(rawName) ||
          'UnknownAlgorithm';

        const modelType =
          item.model_type ||
          item.modelType ||
          item.problem_type ||
          item.problemType ||
          this.inferModelTypeFromClass(originalClass) ||
          selectedProjectType;

        const originalModule =
          item.module ||
          item.library ||
          item.lib ||
          item.original_lib ||
          this.detectModuleFromAlgorithm(originalClass);

        const replacementOptions =
          item.replacement_options ||
          item.replacementOptions ||
          item.available_replacements ||
          item.availableReplacements ||
          item.replacements ||
          this.getDefaultReplacementOptions(modelType);

        const normalizedOptions = this.normalizeReplacementOptions(
          replacementOptions,
          modelType
        );

        const isKnownReplaceable = this.isKnownReplaceableAlgorithm(originalClass);
        const hasReplacementOptions = normalizedOptions.length > 0;

        const isSupported =
          item.supported !== undefined
            ? Boolean(item.supported)
            : isKnownReplaceable && hasReplacementOptions;

        const id =
          item.id ||
          item.occurrence_id ||
          item.occurrenceId ||
          `${originalClass}_${item.line || index}_${item.column || 0}_${index}`;

        return {
          id,
          occurrenceId: item.occurrence_id || item.occurrenceId || id,
          varName: item.var_name || item.varName || item.variable || 'model',
          originalClass,
          originalDisplay:
            item.originalDisplay ||
            item.original_display ||
            item.display ||
            this.formatAlgorithmDisplay(originalClass),
          modelType,
          supported: isSupported,
          module: originalModule,
          line: item.line ?? null,
          column: item.column ?? null,
          replacementOptions: normalizedOptions,
        };
      }
    );

    this.detectedAlgorithms = allDetectedAlgorithms.filter((algorithm) => {
      return (
        algorithm.supported &&
        algorithm.modelType === selectedProjectType &&
        algorithm.replacementOptions.length > 0
      );
    });

    if (this.detectedAlgorithms.length === 0) {
      this.detectedAlgorithms = allDetectedAlgorithms.filter((algorithm) => {
        return algorithm.supported && algorithm.replacementOptions.length > 0;
      });
    }

    this.supportedModules =
      analysis.supported_modules ||
      analysis.supportedModules ||
      analysis.supported ||
      [];

    this.unsupportedModules =
      analysis.unsupported_modules ||
      analysis.unsupportedModules ||
      analysis.unsupported ||
      [];

    this.algorithmSelections = {};

    for (const algorithm of this.detectedAlgorithms) {
      this.algorithmSelections[algorithm.id] = {
        action: 'KEEP',
        selectedModule: '',
        selectedAlgorithm: '',
      };
    }

    if (this.detectedAlgorithms.length === 0 && this.analysisSummary) {
      this.warnings = [
        ...this.warnings,
        'Des algorithmes ont peut-être été détectés, mais aucun mapping de remplacement compatible n’a été trouvé côté interface.',
      ];
    }
  }

  launchPipeline(): void {
    this.errorMessage = '';
    this.successMessage = '';

    const selectedProject = this.getSelectedProject();

    if (!selectedProject) {
      this.errorMessage = 'Veuillez sélectionner un projet.';
      return;
    }

    if (!this.modelName || !this.modelName.trim()) {
      this.errorMessage = 'Veuillez saisir un nom de modèle.';
      return;
    }

    if (!this.runName || !this.runName.trim()) {
      this.errorMessage = 'Veuillez saisir un nom de run.';
      return;
    }

    if (!this.modelFile) {
      this.errorMessage = 'Veuillez sélectionner un fichier modèle Python.';
      return;
    }

    if (this.isCustomDockerfileMode() && !this.dockerfileFile) {
      this.errorMessage = 'Ce projet utilise le mode Dockerfile personnalisé. Veuillez sélectionner un Dockerfile.';
      return;
    }

    const algorithmReplacements: AlgorithmReplacementPayload[] = [];

    this.launching = true;

    const primaryMetric =
      selectedProject.primaryMetric1 || this.getDefaultPrimaryMetric(selectedProject.projectType);

    const direction = this.getMetricDirection(primaryMetric);

    const request: UploadAndRunCustomModelRequest = {
      projectId: selectedProject.id,
      projectName: selectedProject.name,
      modelName: this.modelName.trim(),
      runName: this.runName.trim(),
      primaryMetric,
      direction,
      registeredModelName: this.slugify(selectedProject.name),
      modelFile: this.modelFile,
      datasetFile: this.datasetFile,
      algorithmReplacements,
      useOriginalScript: this.isStandardRuntimeMode(),
      algorithmInjectionEnabled: false,
      replacementEnabled: false,
      runtimeMode: this.getSelectedRuntimeMode(),
      dockerfileFile: this.isCustomDockerfileMode() ? this.dockerfileFile : null,
      requirementsFile: this.isCustomDockerfileMode() ? this.requirementsFile : null,
      customImageUri: this.customImageUri?.trim() || selectedProject.customImageUri || null,
    };

    this.experimentService.uploadAndRunCustomModel(request).subscribe({
      next: (experiment: MlExperimentResponse) => {
        this.launching = false;
        this.successMessage = this.isCustomDockerfileMode()
          ? 'Pipeline lancé en mode Dockerfile personnalisé.'
          : 'Pipeline lancé avec succès. Synchronisation MLflow en cours...';

        const normalized = this.normalizeExperiment(experiment);

        this.experiments = [
          normalized,
          ...this.experiments.filter((item) => item.id !== normalized.id),
        ];

        this.startPollingExperiments();
      },
      error: (error) => {
        this.launching = false;
        this.errorMessage = this.extractErrorMessage(
          error,
          'Erreur lors du lancement du pipeline.'
        );
      },
    });
  }

  resetForm(): void {
    this.modelName = '';
    this.runName = '';
    this.resetFilesOnly();

    const selectedProject = this.getSelectedProject();

    if (selectedProject) {
      this.modelName = `${this.slugify(selectedProject.name)}_model`;
      this.runName = `${this.slugify(selectedProject.name)}_run_${this.getDateSuffix()}`;
      this.customImageUri = selectedProject.customImageUri || '';
    }
  }

  private resetFilesOnly(): void {
    this.modelFile = null;
    this.datasetFile = null;
    this.dockerfileFile = null;
    this.requirementsFile = null;
    this.resetAnalysisState();

    const modelInput = document.getElementById('modelFileInput') as HTMLInputElement | null;
    const datasetInput = document.getElementById('datasetFileInput') as HTMLInputElement | null;
    const dockerfileInput = document.getElementById('dockerfileInput') as HTMLInputElement | null;
    const requirementsInput = document.getElementById('requirementsFileInput') as HTMLInputElement | null;

    if (modelInput) modelInput.value = '';
    if (datasetInput) datasetInput.value = '';
    if (dockerfileInput) dockerfileInput.value = '';
    if (requirementsInput) requirementsInput.value = '';
  }

  getReplacementCount(): number {
    if (this.isCustomDockerfileMode()) {
      return 0;
    }

    return Object.values(this.algorithmSelections).filter(
      (selection) => selection.action === 'REPLACE' && !!selection.selectedAlgorithm
    ).length;
  }

  onReplacementActionChange(algorithm: DetectedAlgorithm): void {
    const selection = this.algorithmSelections[algorithm.id];

    if (!selection) return;

    if (selection.action === 'KEEP') {
      selection.selectedModule = '';
      selection.selectedAlgorithm = '';
      return;
    }

    const modules = this.getAvailableModulesForAlgorithm(algorithm);

    if (modules.length === 1) {
      selection.selectedModule = modules[0];

      const algorithms = this.getAlgorithmsByModule(algorithm, selection.selectedModule);

      if (algorithms.length === 1) {
        selection.selectedAlgorithm = algorithms[0].className;
      }
    }
  }

  onReplacementModuleChange(algorithm: DetectedAlgorithm): void {
    const selection = this.algorithmSelections[algorithm.id];

    if (!selection) return;

    selection.selectedAlgorithm = '';

    const algorithms = this.getAlgorithmsByModule(algorithm, selection.selectedModule);

    if (algorithms.length === 1) {
      selection.selectedAlgorithm = algorithms[0].className;
    }
  }

  onReplacementAlgorithmChange(_algorithm: DetectedAlgorithm): void {}

  getAvailableModulesForAlgorithm(algorithm: DetectedAlgorithm): string[] {
    const options = algorithm.replacementOptions || [];
    const modules = options.map((option) => option.module).filter((module) => !!module);

    return Array.from(new Set(modules));
  }

  getAlgorithmsByModule(algorithm: DetectedAlgorithm, moduleName: string): ReplacementOption[] {
    if (!moduleName) {
      return [];
    }

    return (algorithm.replacementOptions || []).filter(
      (option) => option.module === moduleName
    );
  }

  private buildAlgorithmReplacementsPayload(): AlgorithmReplacementPayload[] {
    const payload: AlgorithmReplacementPayload[] = [];

    for (const algorithm of this.detectedAlgorithms) {
      const selection = this.algorithmSelections[algorithm.id];

      if (!selection) continue;
      if (selection.action !== 'REPLACE') continue;
      if (!selection.selectedAlgorithm || !selection.selectedModule) continue;

      payload.push({
        occurrence_id: algorithm.occurrenceId || algorithm.id,
        var_name: algorithm.varName,
        original_class: algorithm.originalClass,
        new_class: selection.selectedAlgorithm,
        new_lib: selection.selectedModule,
      });
    }

    return payload;
  }

  private normalizeReplacementOptions(options: any[], modelType: string): ReplacementOption[] {
    if (!options || options.length === 0) {
      return this.getDefaultReplacementOptions(modelType);
    }

    return options.map((option: any) => {
      if (typeof option === 'string') {
        return {
          module: this.detectModuleFromAlgorithm(option),
          className: option,
          display: option,
        };
      }

      const className =
        option.className ||
        option.class_name ||
        option.algorithm ||
        option.name ||
        option.new_class ||
        option.newClass ||
        option.display ||
        'UnknownAlgorithm';

      const module =
        option.module ||
        option.library ||
        option.lib ||
        option.new_lib ||
        this.detectModuleFromAlgorithm(className);

      return {
        module,
        className,
        display: option.display || className,
      };
    });
  }

  private getDefaultReplacementOptions(modelType: string): ReplacementOption[] {
    if (modelType === 'regression') {
      return [
        { module: 'sklearn.ensemble', className: 'RandomForestRegressor', display: 'Random Forest Regressor' },
        { module: 'sklearn.ensemble', className: 'GradientBoostingRegressor', display: 'Gradient Boosting Regressor' },
        { module: 'sklearn.ensemble', className: 'ExtraTreesRegressor', display: 'Extra Trees Regressor' },
        { module: 'sklearn.ensemble', className: 'AdaBoostRegressor', display: 'AdaBoost Regressor' },
        { module: 'sklearn.tree', className: 'DecisionTreeRegressor', display: 'Decision Tree Regressor' },
        { module: 'sklearn.neighbors', className: 'KNeighborsRegressor', display: 'K-Nearest Neighbors Regressor' },
        { module: 'sklearn.svm', className: 'SVR', display: 'SVM / SVR' },
        { module: 'sklearn.linear_model', className: 'LinearRegression', display: 'Linear Regression' },
        { module: 'sklearn.linear_model', className: 'Ridge', display: 'Ridge Regression' },
        { module: 'sklearn.linear_model', className: 'Lasso', display: 'Lasso Regression' },
        { module: 'xgboost', className: 'XGBRegressor', display: 'XGBoost Regressor' },
        { module: 'lightgbm', className: 'LGBMRegressor', display: 'LightGBM Regressor' },
      ];
    }

    return [
      { module: 'sklearn.ensemble', className: 'RandomForestClassifier', display: 'Random Forest Classifier' },
      { module: 'sklearn.ensemble', className: 'GradientBoostingClassifier', display: 'Gradient Boosting Classifier' },
      { module: 'sklearn.ensemble', className: 'ExtraTreesClassifier', display: 'Extra Trees Classifier' },
      { module: 'sklearn.ensemble', className: 'AdaBoostClassifier', display: 'AdaBoost Classifier' },
      { module: 'sklearn.tree', className: 'DecisionTreeClassifier', display: 'Decision Tree Classifier' },
      { module: 'sklearn.neighbors', className: 'KNeighborsClassifier', display: 'K-Nearest Neighbors Classifier' },
      { module: 'sklearn.svm', className: 'SVC', display: 'SVM / SVC' },
      { module: 'sklearn.linear_model', className: 'LogisticRegression', display: 'Logistic Regression' },
      { module: 'sklearn.naive_bayes', className: 'GaussianNB', display: 'Gaussian Naive Bayes' },
      { module: 'xgboost', className: 'XGBClassifier', display: 'XGBoost Classifier' },
      { module: 'lightgbm', className: 'LGBMClassifier', display: 'LightGBM Classifier' },
    ];
  }

  normalizeExperiments(experiments: MlExperimentResponse[]): MlExperimentResponse[] {
    return (experiments || []).map((experiment) => this.normalizeExperiment(experiment));
  }

  normalizeExperiment(experiment: MlExperimentResponse): MlExperimentResponse {
    if (!experiment) {
      return experiment;
    }

    return {
      ...experiment,

      modelVersion:
        experiment.modelVersion ??
        experiment.mlflowModelVersion ??
        experiment.latestVersion ??
        experiment.latest_version ??
        experiment['model_version'] ??
        experiment['latestVersion'] ??
        experiment['latest_version'] ??
        null,

      latestVersion:
        experiment.latestVersion ??
        experiment.latest_version ??
        experiment['latestVersion'] ??
        experiment['latest_version'] ??
        experiment.modelVersion ??
        experiment.mlflowModelVersion ??
        experiment['model_version'] ??
        null,

      productionVersion:
        experiment.productionVersion ??
        experiment.productionModelVersion ??
        experiment['production_version'] ??
        experiment['productionVersion'] ??
        null,

      detectedAlgorithms:
        experiment.detectedAlgorithms ??
        experiment.detected_algorithms ??
        experiment['detectedAlgorithms'] ??
        experiment['detected_algorithms'] ??
        null,

      usedAlgorithms:
        experiment.usedAlgorithms ??
        experiment.used_algorithms ??
        experiment['usedAlgorithms'] ??
        experiment['used_algorithms'] ??
        null,

      algorithmReplacements:
        experiment.algorithmReplacements ??
        experiment.algorithm_replacements ??
        experiment['algorithmReplacements'] ??
        experiment['algorithm_replacements'] ??
        null,

      algorithmReplacementCount:
        experiment.algorithmReplacementCount ??
        experiment.algorithm_replacement_count ??
        experiment['algorithmReplacementCount'] ??
        experiment['algorithm_replacement_count'] ??
        0,

      hasAlgorithmReplacement:
        experiment.hasAlgorithmReplacement ??
        experiment.has_algorithm_replacement ??
        experiment['hasAlgorithmReplacement'] ??
        experiment['has_algorithm_replacement'] ??
        false,

      runtimeMode:
        experiment.runtimeMode ??
        experiment.runtime_mode ??
        experiment['runtimeMode'] ??
        experiment['runtime_mode'] ??
        'STANDARD_RUNTIME',

      customImageUri:
        experiment.customImageUri ??
        experiment.custom_image_uri ??
        experiment['customImageUri'] ??
        experiment['custom_image_uri'] ??
        null,

      dockerfilePath:
        experiment.dockerfilePath ??
        experiment.dockerfile_path ??
        experiment['dockerfilePath'] ??
        experiment['dockerfile_path'] ??
        null,

      requirementsPath:
        experiment.requirementsPath ??
        experiment.requirements_path ??
        experiment['requirementsPath'] ??
        experiment['requirements_path'] ??
        null,

      buildStatus:
        experiment.buildStatus ??
        experiment.build_status ??
        experiment['buildStatus'] ??
        experiment['build_status'] ??
        null,

      buildLogs:
        experiment.buildLogs ??
        experiment.build_logs ??
        experiment['buildLogs'] ??
        experiment['build_logs'] ??
        null,
    };
  }

  private hasValidScoreForVersioning(experiment: MlExperimentResponse): boolean {
    const primaryMetricValue =
      experiment.primaryMetricValue ??
      experiment['primary_metric_value'] ??
      null;

    const globalScore =
      experiment.globalScore ??
      experiment['global_score'] ??
      null;

    return primaryMetricValue !== null &&
      primaryMetricValue !== undefined &&
      globalScore !== null &&
      globalScore !== undefined;
  }

  getModelVersion(experiment: MlExperimentResponse): string {
    if (!this.hasValidScoreForVersioning(experiment) || experiment.status !== 'COMPLETED') {
      return '-';
    }

    const value =
      experiment.modelVersion ??
      experiment.mlflowModelVersion ??
      experiment.latestVersion ??
      experiment.latest_version ??
      experiment['model_version'] ??
      experiment['latestVersion'] ??
      experiment['latest_version'] ??
      null;

    return value === null || value === undefined || value === '' ? '-' : String(value);
  }

  getProductionVersion(experiment: MlExperimentResponse): string {
    if (!this.isProductionExperiment(experiment)) {
      return '-';
    }

    const value =
      experiment.productionVersion ??
      experiment.productionModelVersion ??
      experiment['production_version'] ??
      experiment['productionVersion'] ??
      experiment.modelVersion ??
      experiment.mlflowModelVersion ??
      experiment['model_version'] ??
      null;

    return value === null || value === undefined || value === '' ? '-' : String(value);
  }

  isProductionExperiment(experiment: MlExperimentResponse): boolean {
    return this.toBoolean(experiment.production ?? experiment.isProduction ?? experiment['is_production']) &&
      experiment.status === 'COMPLETED' &&
      this.hasValidScoreForVersioning(experiment) &&
      this.getModelVersion(experiment) !== '-';
  }

  hasAlgorithmReplacementForRun(experiment: MlExperimentResponse): boolean {
    const cleanList = this.getCleanReplacementList(experiment);

    if (cleanList.length > 0) {
      return true;
    }

    const explicit = experiment.hasAlgorithmReplacement ?? experiment.has_algorithm_replacement;

    if (explicit !== undefined && explicit !== null) {
      return this.toBoolean(explicit);
    }

    return this.getAlgorithmReplacementCountForRun(experiment) > 0;
  }

  getAlgorithmReplacementCountForRun(experiment: MlExperimentResponse): number {
    const cleanList = this.getCleanReplacementList(experiment);

    if (cleanList.length > 0) {
      return cleanList.length;
    }

    const value =
      experiment.algorithmReplacementCount ??
      experiment.algorithm_replacement_count ??
      experiment['algorithmReplacementCount'] ??
      experiment['algorithm_replacement_count'] ??
      0;

    if (typeof value === 'number') {
      return value;
    }

    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  getDetectedAlgorithmsForRun(_experiment: MlExperimentResponse): any[] {
    return [];
  }

  getUsedAlgorithmsForRun(_experiment: MlExperimentResponse): any[] {
    return [];
  }

  getAlgorithmReplacementsForRun(experiment: MlExperimentResponse): any[] {
    return this.parseJsonArray(experiment.algorithmReplacements ?? experiment.algorithm_replacements);
  }

  getCleanReplacementList(experiment: MlExperimentResponse): any[] {
    const replacements = this.getAlgorithmReplacementsForRun(experiment);

    return replacements.filter((replacement) => {
      if (!replacement) {
        return false;
      }

      if (typeof replacement === 'string') {
        try {
          const parsed = JSON.parse(replacement);
          return this.getCleanReplacementList({
            ...experiment,
            algorithmReplacements: [parsed],
          } as MlExperimentResponse).length > 0;
        } catch {
          return replacement.includes('→');
        }
      }

      return Boolean(
        replacement.from ||
        replacement.to ||
        replacement.original_class ||
        replacement.originalClass ||
        replacement.new_class ||
        replacement.newClass ||
        replacement.oldAlgorithm ||
        replacement.newAlgorithm ||
        replacement.old_algorithm ||
        replacement.new_algorithm ||
        replacement.target_class ||
        replacement.targetClass
      );
    });
  }

  getAlgorithmLabel(value: any): string {
    if (!value) {
      return '-';
    }

    if (typeof value === 'string') {
      return value;
    }

    return (
      value.display ||
      value.originalDisplay ||
      value.original_display ||
      value.className ||
      value.class_name ||
      value.originalClass ||
      value.original_class ||
      value.canonicalClass ||
      value.canonical_class ||
      value.class ||
      value.name ||
      value.algorithm ||
      '-'
    );
  }

  getReplacementLabel(value: any): string {
    if (!value) {
      return '-';
    }

    if (typeof value === 'string') {
      try {
        const parsed = JSON.parse(value);
        return this.getReplacementLabel(parsed);
      } catch {
        return value;
      }
    }

    const oldAlgorithm =
      value.oldAlgorithm ||
      value.old_algorithm ||
      value.from ||
      value.original_class ||
      value.originalClass ||
      value.old_class ||
      value.oldClass ||
      value.source_class ||
      value.sourceClass ||
      value.original ||
      '-';

    const newAlgorithm =
      value.newAlgorithm ||
      value.new_algorithm ||
      value.to ||
      value.new_class ||
      value.newClass ||
      value.target_class ||
      value.targetClass ||
      value.replacement_class ||
      value.replacementClass ||
      value.replacement ||
      '-';

    return `${oldAlgorithm} → ${newAlgorithm}`;
  }

  getReplacementStatusLabel(value: any): string {
    if (!value || typeof value !== 'object') {
      return '';
    }

    const status =
      value.status ||
      value.replacement_status ||
      value.replacementStatus ||
      '';

    return status ? `Statut : ${status}` : '';
  }

  getAlgorithmSubLabel(value: any): string {
    return this.getReplacementStatusLabel(value);
  }

  getShortMessage(experiment: MlExperimentResponse): string {
    const message = experiment.message || '';

    if (!message) {
      return '-';
    }

    return message.length > 140 ? `${message.slice(0, 140)}...` : message;
  }

  getStatusClass(status: string): string {
    const normalized = String(status || '').toUpperCase();

    if (normalized === 'COMPLETED' || normalized === 'FINISHED' || normalized === 'SUCCEEDED') {
      return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300';
    }

    if (normalized === 'FAILED' || normalized === 'ERROR' || normalized === 'BUILD_FAILED') {
      return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300';
    }

    if (
      normalized === 'RUNNING' ||
      normalized === 'PENDING' ||
      normalized === 'CREATED' ||
      normalized === 'BUILDING_IMAGE' ||
      normalized === 'IMAGE_READY'
    ) {
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300';
    }

    return 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300';
  }

  formatMetric(metric?: string | null): string {
    if (!metric) {
      return '-';
    }

    return String(metric)
      .replace(/_/g, ' ')
      .replace(/\b\w/g, (char) => char.toUpperCase());
  }

  formatMetricValue(value: any): string {
    if (value === null || value === undefined || value === '') {
      return '-';
    }

    const numeric = Number(value);

    if (!Number.isFinite(numeric)) {
      return String(value);
    }

    return numeric.toFixed(4);
  }

  formatGlobalScore(value: any): string {
    if (value === null || value === undefined || value === '') {
      return '-';
    }

    const numeric = Number(value);

    if (!Number.isFinite(numeric)) {
      return String(value);
    }

    return `${(numeric * 100).toFixed(2)}%`;
  }

  formatDate(value?: string | null): string {
    if (!value || value === '-') {
      return '-';
    }

    const date = this.parseBackendDate(value);

    if (!date) {
      return String(value);
    }

    return date.toLocaleString('fr-FR', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  private parseBackendDate(value: string | null | undefined): Date | null {
    if (!value) {
      return null;
    }

    const clean = String(value).trim();

    if (!clean || clean === '-') {
      return null;
    }

    /*
     * Si le backend renvoie une date sans timezone, on la considère UTC.
     * Cela évite les décalages entre Windows, Docker, AKS et le navigateur.
     */
    const hasTimezone = /([zZ]|[+-]\d{2}:?\d{2})$/.test(clean);
    const normalized = hasTimezone ? clean : `${clean}Z`;

    const date = new Date(normalized);

    if (Number.isNaN(date.getTime())) {
      return null;
    }

    return date;
  }

  getRunDisplayDate(experiment: MlExperimentResponse): string {
    /*
     * Date principale du run :
     * 1. startedAt : vraie date de début du run si disponible
     * 2. createdAt : date de création de la ligne sinon
     *
     * Ne pas utiliser updatedAt ici, car updatedAt change à chaque sync MLflow.
     */
    return this.formatDate(
      experiment.startedAt ||
      experiment['started_at'] ||
      experiment.createdAt ||
      experiment['created_at'] ||
      '-'
    );
  }

  getRunFinishedDate(experiment: MlExperimentResponse): string {
    return this.formatDate(
      experiment.finishedAt ||
      experiment['finished_at'] ||
      '-'
    );
  }

  hasRunFinishedDate(experiment: MlExperimentResponse): boolean {
    return Boolean(experiment.finishedAt || experiment['finished_at']);
  }

  getModuleKey(module: any): string {
    return typeof module === 'string' ? module : module?.name || module?.module || JSON.stringify(module);
  }

  getModuleLabel(module: any): string {
    if (!module) {
      return '-';
    }

    if (typeof module === 'string') {
      return module;
    }

    return module.display || module.name || module.module || module.className || JSON.stringify(module);
  }

  private parseJsonArray(value: any): any[] {
    if (!value) {
      return [];
    }

    if (Array.isArray(value)) {
      return value;
    }

    if (typeof value !== 'string') {
      return [value];
    }

    const trimmed = value.trim();

    if (!trimmed) {
      return [];
    }

    try {
      const parsed = JSON.parse(trimmed);

      if (Array.isArray(parsed)) {
        return parsed;
      }

      return parsed ? [parsed] : [];
    } catch {
      return [trimmed];
    }
  }

  private toBoolean(value: any): boolean {
    if (typeof value === 'boolean') {
      return value;
    }

    if (typeof value === 'number') {
      return value > 0;
    }

    if (typeof value === 'string') {
      const normalized = value.trim().toLowerCase();
      return ['true', '1', 'yes', 'y', 'oui'].includes(normalized);
    }

    return false;
  }

  private getDefaultPrimaryMetric(projectType: string): string {
    return String(projectType || '').toLowerCase().includes('regression') ? 'mae' : 'accuracy';
  }

  private getMetricDirection(metric: string): 'min' | 'max' {
    const normalized = String(metric || '').toLowerCase();

    if (
      normalized.includes('loss') ||
      normalized.includes('error') ||
      normalized.includes('mae') ||
      normalized.includes('mse') ||
      normalized.includes('rmse')
    ) {
      return 'min';
    }

    return 'max';
  }

  private extractErrorMessage(error: any, fallback: string): string {
    if (!error) {
      return fallback;
    }

    if (typeof error === 'string') {
      return error;
    }

    if (error.error) {
      if (typeof error.error === 'string') {
        return error.error;
      }

      if (error.error.message) {
        return error.error.message;
      }

      if (error.error.error) {
        return error.error.error;
      }
    }

    return error.message || fallback;
  }

  private slugify(value: string): string {
    if (!value) {
      return 'project';
    }

    return value
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+/, '')
      .replace(/-+$/, '') || 'project';
  }

  private getDateSuffix(): string {
    const now = new Date();
    const pad = (value: number) => String(value).padStart(2, '0');

    return [
      now.getFullYear(),
      pad(now.getMonth() + 1),
      pad(now.getDate()),
      '_',
      pad(now.getHours()),
      pad(now.getMinutes()),
      pad(now.getSeconds()),
    ].join('');
  }

  private inferAlgorithmsFromSummary(text: string): any[] {
    if (!text) {
      return [];
    }

    const candidates = [
      'RandomForestClassifier',
      'RandomForestRegressor',
      'GradientBoostingClassifier',
      'GradientBoostingRegressor',
      'ExtraTreesClassifier',
      'ExtraTreesRegressor',
      'AdaBoostClassifier',
      'AdaBoostRegressor',
      'DecisionTreeClassifier',
      'DecisionTreeRegressor',
      'KNeighborsClassifier',
      'KNeighborsRegressor',
      'SVC',
      'SVR',
      'LogisticRegression',
      'LinearRegression',
      'Ridge',
      'Lasso',
      'ElasticNet',
      'GaussianNB',
      'XGBClassifier',
      'XGBRegressor',
      'LGBMClassifier',
      'LGBMRegressor',
    ];

    const found: any[] = [];

    for (const candidate of candidates) {
      if (text.includes(candidate)) {
        found.push({
          className: candidate,
          display: this.formatAlgorithmDisplay(candidate),
          supported: true,
          modelType: this.inferModelTypeFromClass(candidate) || this.getSelectedProjectType(),
          module: this.detectModuleFromAlgorithm(candidate),
          replacementOptions: this.getDefaultReplacementOptions(
            this.inferModelTypeFromClass(candidate) || this.getSelectedProjectType()
          ),
        });
      }
    }

    return found;
  }

  private normalizeFriendlyAlgorithmName(value: string, type: ProjectType): string | null {
    if (!value) {
      return null;
    }

    const normalized = value.toLowerCase();

    if (normalized.includes('random forest')) {
      return type === 'regression' ? 'RandomForestRegressor' : 'RandomForestClassifier';
    }

    if (normalized.includes('gradient boosting')) {
      return type === 'regression' ? 'GradientBoostingRegressor' : 'GradientBoostingClassifier';
    }

    if (normalized.includes('extra trees')) {
      return type === 'regression' ? 'ExtraTreesRegressor' : 'ExtraTreesClassifier';
    }

    if (normalized.includes('adaboost') || normalized.includes('ada boost')) {
      return type === 'regression' ? 'AdaBoostRegressor' : 'AdaBoostClassifier';
    }

    if (normalized.includes('decision tree')) {
      return type === 'regression' ? 'DecisionTreeRegressor' : 'DecisionTreeClassifier';
    }

    if (normalized.includes('nearest') || normalized.includes('knn')) {
      return type === 'regression' ? 'KNeighborsRegressor' : 'KNeighborsClassifier';
    }

    if (normalized.includes('svc') || normalized.includes('svm')) {
      return type === 'regression' ? 'SVR' : 'SVC';
    }

    if (normalized.includes('logistic')) {
      return 'LogisticRegression';
    }

    if (normalized.includes('linear')) {
      return 'LinearRegression';
    }

    if (normalized.includes('ridge')) {
      return 'Ridge';
    }

    if (normalized.includes('lasso')) {
      return 'Lasso';
    }

    if (normalized.includes('gaussian')) {
      return 'GaussianNB';
    }

    if (normalized.includes('xgboost') || normalized.includes('xgb')) {
      return type === 'regression' ? 'XGBRegressor' : 'XGBClassifier';
    }

    if (normalized.includes('lightgbm') || normalized.includes('lgbm')) {
      return type === 'regression' ? 'LGBMRegressor' : 'LGBMClassifier';
    }

    return null;
  }

  private normalizeDetectedAlgorithmName(value: string): string | null {
    if (!value) {
      return null;
    }

    const normalized = value.trim();
    const compact = normalized.toLowerCase().replace(/[^a-z0-9]/g, '');

    const mapping: Record<string, string> = {
      randomforestclassifier: 'RandomForestClassifier',
      randomforestregressor: 'RandomForestRegressor',
      gradientboostingclassifier: 'GradientBoostingClassifier',
      gradientboostingregressor: 'GradientBoostingRegressor',
      extratreesclassifier: 'ExtraTreesClassifier',
      extratreesregressor: 'ExtraTreesRegressor',
      adaboostclassifier: 'AdaBoostClassifier',
      adaboostregressor: 'AdaBoostRegressor',
      decisiontreeclassifier: 'DecisionTreeClassifier',
      decisiontreeregressor: 'DecisionTreeRegressor',
      kneighborsclassifier: 'KNeighborsClassifier',
      kneighborsregressor: 'KNeighborsRegressor',
      svc: 'SVC',
      svr: 'SVR',
      logisticregression: 'LogisticRegression',
      linearregression: 'LinearRegression',
      ridge: 'Ridge',
      lasso: 'Lasso',
      elasticnet: 'ElasticNet',
      gaussiannb: 'GaussianNB',
      xgbclassifier: 'XGBClassifier',
      xgbregressor: 'XGBRegressor',
      lgbmclassifier: 'LGBMClassifier',
      lgbmregressor: 'LGBMRegressor',
    };

    return mapping[compact] || normalized;
  }

  private formatAlgorithmDisplay(className: string): string {
    const mapping: Record<string, string> = {
      RandomForestClassifier: 'Random Forest Classifier',
      RandomForestRegressor: 'Random Forest Regressor',
      GradientBoostingClassifier: 'Gradient Boosting Classifier',
      GradientBoostingRegressor: 'Gradient Boosting Regressor',
      ExtraTreesClassifier: 'Extra Trees Classifier',
      ExtraTreesRegressor: 'Extra Trees Regressor',
      AdaBoostClassifier: 'AdaBoost Classifier',
      AdaBoostRegressor: 'AdaBoost Regressor',
      DecisionTreeClassifier: 'Decision Tree Classifier',
      DecisionTreeRegressor: 'Decision Tree Regressor',
      KNeighborsClassifier: 'K-Nearest Neighbors Classifier',
      KNeighborsRegressor: 'K-Nearest Neighbors Regressor',
      SVC: 'SVM / SVC',
      SVR: 'SVM / SVR',
      LogisticRegression: 'Logistic Regression',
      LinearRegression: 'Linear Regression',
      Ridge: 'Ridge Regression',
      Lasso: 'Lasso Regression',
      ElasticNet: 'Elastic Net',
      GaussianNB: 'Gaussian Naive Bayes',
      XGBClassifier: 'XGBoost Classifier',
      XGBRegressor: 'XGBoost Regressor',
      LGBMClassifier: 'LightGBM Classifier',
      LGBMRegressor: 'LightGBM Regressor',
    };

    return mapping[className] || className;
  }

  private detectModuleFromAlgorithm(className: string): string {
    const normalized = this.normalizeDetectedAlgorithmName(className) || className;

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
        'ElasticNet',
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

  private inferModelTypeFromClass(className: string): string | null {
    if (!className) {
      return null;
    }

    if (
      className.includes('Regressor') ||
      className === 'SVR' ||
      className === 'LinearRegression' ||
      className === 'Ridge' ||
      className === 'Lasso'
    ) {
      return 'regression';
    }

    if (
      className.includes('Classifier') ||
      className === 'SVC' ||
      className === 'LogisticRegression' ||
      className === 'GaussianNB'
    ) {
      return 'classification';
    }

    return null;
  }

  private isKnownReplaceableAlgorithm(className: string): boolean {
    if (!className) {
      return false;
    }

    const knownAlgorithms = [
      'RandomForestClassifier',
      'RandomForestRegressor',
      'GradientBoostingClassifier',
      'GradientBoostingRegressor',
      'ExtraTreesClassifier',
      'ExtraTreesRegressor',
      'LogisticRegression',
      'LinearRegression',
      'Ridge',
      'Lasso',
      'SVC',
      'SVR',
      'DecisionTreeClassifier',
      'DecisionTreeRegressor',
      'KNeighborsClassifier',
      'KNeighborsRegressor',
      'GaussianNB',
      'XGBClassifier',
      'XGBRegressor',
      'LGBMClassifier',
      'LGBMRegressor',
    ];

    return knownAlgorithms.includes(className);
  }
}