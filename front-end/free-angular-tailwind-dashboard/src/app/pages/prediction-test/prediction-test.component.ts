import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  PredictionRequest,
  PredictionResponse,
  PredictionService,
  ProductionModel,
} from '../../core/services/prediction.service';

type PredictionUiMode = 'TABULAR' | 'IMAGE' | 'TEXT';
type NormalizedDataType = 'TABULAR' | 'IMAGE' | 'TEXT';

@Component({
  selector: 'app-prediction-test',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './prediction-test.component.html',
  styleUrls: ['./prediction-test.component.css'],
})
export class PredictionTestComponent implements OnInit {
  productionModels: ProductionModel[] = [];
  selectedModelName = '';

  selectedMode: PredictionUiMode = 'TABULAR';

  inputJson = `{
  "major": "Computer Science",
  "scholarship": "yes",
  "study_hours_per_week": 18,
  "attendance_rate": 0.92,
  "previous_grade": 15.5,
  "assignments_completed": 0.9,
  "sleep_hours": 7,
  "internet_access": 1,
  "extracurricular_hours": 3
}`;

  inputText = `le service est excellent et rapide`;

  selectedImageFile: File | null = null;
  imagePreviewUrl: string | null = null;

  loadingModels = false;
  loadingPrediction = false;

  errorMessage = '';
  successMessage = '';

  predictionResponse: PredictionResponse | null = null;

  constructor(private predictionService: PredictionService) {}

  ngOnInit(): void {
    this.loadProductionModels();
  }

  /*
   * Cette méthode garde ton ancienne logique,
   * mais maintenant le service charge /api/model-deployments/visible.
   *
   * Donc la liste contient :
   * - les modèles du user connecté
   * - les modèles PUBLIC_API
   * - les modèles SHARED autorisés
   */
  loadProductionModels(): void {
    this.loadingModels = true;
    this.errorMessage = '';
    this.successMessage = '';
    this.predictionResponse = null;

    this.predictionService.getMyProductionModels().subscribe({
      next: (models) => {
        this.productionModels = (models || []).map((model) =>
          this.normalizeProductionModel(model)
        );

        if (this.productionModels.length > 0) {
          this.selectedModelName = this.productionModels[0].registeredModelName;
          this.applyModeFromSelectedModel();
        } else {
          this.selectedModelName = '';
          this.selectedMode = 'TABULAR';
        }

        this.loadingModels = false;
      },
      error: (error) => {
        this.errorMessage =
          error?.message || 'Impossible de charger les modèles utilisables.';
        this.loadingModels = false;
      },
    });
  }

  refreshModels(): void {
    this.loadProductionModels();
  }

  onModelChange(): void {
    this.predictionResponse = null;
    this.errorMessage = '';
    this.successMessage = '';
    this.resetPredictionInputs();
    this.applyModeFromSelectedModel();
  }

  private normalizeProductionModel(model: ProductionModel): ProductionModel {
    const normalizedDataType = this.normalizeDataType(
      (model as any).dataType ??
        (model as any).data_type ??
        (model as any).inputType ??
        (model as any).input_type ??
        (model as any).projectDataType ??
        (model as any).project_data_type
    );

    return {
      ...model,
      dataType: normalizedDataType,
      inputType: normalizedDataType,
    } as ProductionModel;
  }

  normalizeDataType(value: any): NormalizedDataType {
    const dataType = String(value || 'TABULAR')
      .trim()
      .toUpperCase()
      .replace('-', '_')
      .replace(' ', '_');

    if (
      dataType === 'TEXT' ||
      dataType === 'NLP' ||
      dataType === 'LLM' ||
      dataType === 'TEXT_NLP' ||
      dataType === 'TEXTE_NLP' ||
      dataType === 'TEXTE'
    ) {
      return 'TEXT';
    }

    if (
      dataType === 'IMAGE' ||
      dataType === 'VISION' ||
      dataType === 'IMAGES'
    ) {
      return 'IMAGE';
    }

    return 'TABULAR';
  }

  get selectedModel(): ProductionModel | null {
    if (!this.selectedModelName) {
      return null;
    }

    return (
      this.productionModels.find(
        (model) => model.registeredModelName === this.selectedModelName
      ) || null
    );
  }

  get selectedModelDataType(): NormalizedDataType {
    const model = this.selectedModel;

    if (!model) {
      return 'TABULAR';
    }

    return this.normalizeDataType(
      (model as any).dataType ??
        (model as any).data_type ??
        (model as any).inputType ??
        (model as any).input_type ??
        (model as any).projectDataType ??
        (model as any).project_data_type
    );
  }

  get expectedMode(): PredictionUiMode {
    return this.getPredictionModeFromDataType(this.selectedModelDataType);
  }

  getPredictionModeFromDataType(dataType: any): PredictionUiMode {
    const normalized = this.normalizeDataType(dataType);

    if (normalized === 'TEXT') {
      return 'TEXT';
    }

    if (normalized === 'IMAGE') {
      return 'IMAGE';
    }

    return 'TABULAR';
  }

  applyModeFromSelectedModel(): void {
    this.selectedMode = this.expectedMode;
  }

  canUseMode(mode: PredictionUiMode): boolean {
    if (!this.selectedModel) {
      return false;
    }

    return mode === this.expectedMode;
  }

  onModeChange(mode: PredictionUiMode): void {
    if (!this.canUseMode(mode)) {
      this.selectedMode = this.expectedMode;
      this.errorMessage =
        `Mode non autorisé pour ce modèle. ` +
        `Type de données : ${this.formatDataType(this.selectedModelDataType)}. ` +
        `Mode attendu : ${this.getModeLabel(this.expectedMode)}.`;

      setTimeout(() => {
        if (this.errorMessage?.startsWith('Mode non autorisé')) {
          this.errorMessage = '';
        }
      }, 3500);

      return;
    }

    this.selectedMode = mode;
    this.predictionResponse = null;
    this.errorMessage = '';
    this.successMessage = '';
  }

  predict(): void {
    this.errorMessage = '';
    this.successMessage = '';

    if (!this.selectedModel) {
      this.errorMessage = 'Sélectionne un modèle déployé.';
      return;
    }

    const expected = this.expectedMode;

    if (this.selectedMode !== expected) {
      this.selectedMode = expected;
      this.errorMessage =
        `Le mode de prédiction a été corrigé automatiquement vers ` +
        `${this.getModeLabel(expected)} selon le type de données du modèle.`;
      return;
    }

    if (this.selectedMode === 'IMAGE') {
      this.predictImage();
      return;
    }

    if (this.selectedMode === 'TEXT') {
      this.predictText();
      return;
    }

    this.predictTabular();
  }

  predictTabular(): void {
    this.errorMessage = '';
    this.successMessage = '';
    this.predictionResponse = null;

    if (!this.selectedModelName) {
      this.errorMessage = 'Sélectionne un modèle déployé.';
      return;
    }

    let parsedRow: Record<string, unknown>;

    try {
      parsedRow = JSON.parse(this.inputJson);
    } catch {
      this.errorMessage = 'Le JSON saisi est invalide.';
      return;
    }

    if (!parsedRow || typeof parsedRow !== 'object' || Array.isArray(parsedRow)) {
      this.errorMessage =
        'Le JSON doit représenter une seule ligne sous forme d’objet.';
      return;
    }

    const request: PredictionRequest = {
      rows: [parsedRow],
      modelName: this.selectedModelName,
      modelAlias: 'production',
    };

    this.loadingPrediction = true;

    this.predictionService.predictTabular(request).subscribe({
      next: (response) => {
        this.predictionResponse = response;
        this.successMessage = 'Prédiction tabulaire effectuée avec succès.';
        this.loadingPrediction = false;
      },
      error: (error) => {
        this.errorMessage = error?.message || 'Erreur pendant la prédiction.';
        this.loadingPrediction = false;
      },
    });
  }

  predictImage(): void {
    this.errorMessage = '';
    this.successMessage = '';
    this.predictionResponse = null;

    if (!this.selectedModelName) {
      this.errorMessage = 'Sélectionne un modèle déployé.';
      return;
    }

    if (!this.selectedImageFile) {
      this.errorMessage = 'Sélectionne une image à prédire.';
      return;
    }

    this.loadingPrediction = true;

    this.predictionService
      .predictImage(this.selectedModelName, this.selectedImageFile, 'production')
      .subscribe({
        next: (response) => {
          this.predictionResponse = response;
          this.successMessage = 'Prédiction image effectuée avec succès.';
          this.loadingPrediction = false;
        },
        error: (error) => {
          this.errorMessage =
            error?.message || 'Erreur pendant la prédiction image.';
          this.loadingPrediction = false;
        },
      });
  }

  predictText(): void {
    this.errorMessage = '';
    this.successMessage = '';
    this.predictionResponse = null;

    if (!this.selectedModelName) {
      this.errorMessage = 'Sélectionne un modèle déployé.';
      return;
    }

    if (!this.inputText || this.inputText.trim() === '') {
      this.errorMessage = 'Saisis un texte à prédire.';
      return;
    }

    this.loadingPrediction = true;

    this.predictionService
      .predictText({
        modelName: this.selectedModelName,
        modelAlias: 'production',
        text: this.inputText.trim(),
      })
      .subscribe({
        next: (response) => {
          this.predictionResponse = response;
          this.successMessage = 'Prédiction texte effectuée avec succès.';
          this.loadingPrediction = false;
        },
        error: (error) => {
          this.errorMessage =
            error?.message || 'Erreur pendant la prédiction texte.';
          this.loadingPrediction = false;
        },
      });
  }

  onImageFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] || null;

    this.selectedImageFile = null;
    this.imagePreviewUrl = null;
    this.errorMessage = '';
    this.successMessage = '';
    this.predictionResponse = null;

    if (!file) {
      return;
    }

    this.selectedImageFile = file;

    if (this.imagePreviewUrl) {
      URL.revokeObjectURL(this.imagePreviewUrl);
    }

    this.imagePreviewUrl = URL.createObjectURL(file);
  }

  resetForm(): void {
    this.errorMessage = '';
    this.successMessage = '';
    this.predictionResponse = null;

    this.inputJson = `{
  "major": "Computer Science",
  "scholarship": "yes",
  "study_hours_per_week": 18,
  "attendance_rate": 0.92,
  "previous_grade": 15.5,
  "assignments_completed": 0.9,
  "sleep_hours": 7,
  "internet_access": 1,
  "extracurricular_hours": 3
}`;

    this.inputText = `le service est excellent et rapide`;
    this.selectedImageFile = null;

    if (this.imagePreviewUrl) {
      URL.revokeObjectURL(this.imagePreviewUrl);
    }

    this.imagePreviewUrl = null;

    const imageInput = document.getElementById(
      'imageFileInput'
    ) as HTMLInputElement | null;

    if (imageInput) {
      imageInput.value = '';
    }

    this.applyModeFromSelectedModel();
  }

  resetPredictionInputs(): void {
    this.predictionResponse = null;
    this.errorMessage = '';
    this.successMessage = '';
    this.selectedImageFile = null;

    if (this.imagePreviewUrl) {
      URL.revokeObjectURL(this.imagePreviewUrl);
    }

    this.imagePreviewUrl = null;

    const imageInput = document.getElementById(
      'imageFileInput'
    ) as HTMLInputElement | null;

    if (imageInput) {
      imageInput.value = '';
    }
  }

  get selectedModeLabel(): string {
    return this.getModeLabel(this.selectedMode);
  }

  get selectedModeDescription(): string {
    if (this.selectedMode === 'TEXT') {
      return 'Saisie texte brute pour un modèle NLP : sentiment, classification de texte, etc.';
    }

    if (this.selectedMode === 'IMAGE') {
      return 'Upload d’une image pour un modèle vision.';
    }

    return 'Saisie JSON pour un modèle tabulaire.';
  }

  get expectedModeLabel(): string {
    return this.getModeLabel(this.expectedMode);
  }

  getModeLabel(mode: PredictionUiMode): string {
    if (mode === 'TEXT') {
      return 'Texte / NLP';
    }

    if (mode === 'IMAGE') {
      return 'Image';
    }

    return 'JSON tabulaire';
  }

  formatDataType(value: any): string {
    const dataType = this.normalizeDataType(value);

    if (dataType === 'TEXT') {
      return 'Texte / NLP';
    }

    if (dataType === 'IMAGE') {
      return 'Image';
    }

    return 'Tabulaire';
  }

  formatScore(value: number | null | undefined): string {
    if (value === null || value === undefined || Number.isNaN(Number(value))) {
      return '-';
    }

    const numericValue = Number(value);

    if (numericValue <= 1) {
      return `${(numericValue * 100).toFixed(2)}%`;
    }

    return `${numericValue.toFixed(2)}%`;
  }

  get firstPrediction(): string {
    const response: any = this.predictionResponse;

    if (!response) {
      return '-';
    }

    const prediction =
      response.prediction ??
      response.predictions?.[0] ??
      response.rawResponse?.prediction ??
      response.rawResponse?.predictions?.[0] ??
      response.result ??
      response.label;

    if (prediction === null || prediction === undefined) {
      return '-';
    }

    if (typeof prediction === 'object') {
      return JSON.stringify(prediction);
    }

    return String(prediction);
  }

  formatJson(value: unknown): string {
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }
}