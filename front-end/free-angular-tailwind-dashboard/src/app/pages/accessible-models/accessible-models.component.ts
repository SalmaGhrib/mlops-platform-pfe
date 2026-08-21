import { CommonModule } from '@angular/common';
import { Component, OnInit, ViewChild } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AccessibleModel,
  ModelRegistryService
} from '../../core/services/model-registry.service';
import { SmartSearchComponent } from '../../shared/components/common/smart-search/smart-search.component';
import {
  SearchPageResponse,
  SmartSearchParams
} from '../../shared/components/common/smart-search/smart-search.types';

@Component({
  selector: 'app-accessible-models',
  standalone: true,
  imports: [CommonModule, SmartSearchComponent],
  templateUrl: './accessible-models.component.html',
  styleUrl: './accessible-models.component.css'
})
export class AccessibleModelsComponent implements OnInit {
  @ViewChild(SmartSearchComponent) smartSearch!: SmartSearchComponent<AccessibleModel>;

  models: AccessibleModel[] = [];
  totalModels = 0;

  loading = false;
  errorMessage = '';
  successMessage = '';

  constructor(private modelRegistryService: ModelRegistryService) {}

  ngOnInit(): void {
    // Le composant app-smart-search charge automatiquement la première page.
  }

  loadAccessibleModels(): void {
    this.smartSearch?.resetAndReload();
  }

  searchAccessibleModels = (params: SmartSearchParams): Observable<SearchPageResponse<AccessibleModel>> =>
    this.modelRegistryService.searchDeployments('accessible', params);

  onSearchItems(models: AccessibleModel[]): void {
    this.models = (models || [])
      .filter((model) => this.isUsableModel(model))
      .filter((model) => this.isAccessibleExternalModel(model));

    this.loading = false;
  }

  onTotalChange(total: number): void {
    this.totalModels = total;
  }

  /*
   * Un modèle utilisable doit être réellement déployé.
   */
  private isUsableModel(model: AccessibleModel): boolean {
    return (
      !!model &&
      model.deploymentStatus === 'DEPLOYED' &&
      model.active === true &&
      !!model.predictionEndpoint
    );
  }

  /*
   * Page Accessible Models :
   * on affiche seulement les modèles d'autres utilisateurs.
   *
   * On exclut :
   * - les modèles PRIVATE
   * - les modèles appartenant au user connecté
   *
   * On garde :
   * - PUBLIC_API d'autres users
   * - SHARED d'autres users
   */
  private isAccessibleExternalModel(model: AccessibleModel): boolean {
    if (!model) {
      return false;
    }

    if (model.visibility === 'PRIVATE') {
      return false;
    }

    if (model.visibility !== 'PUBLIC_API' && model.visibility !== 'SHARED') {
      return false;
    }

    return !this.isOwnedByCurrentUser(model);
  }

  /*
   * Essaie de savoir si le modèle appartient au user connecté.
   * Cette méthode supporte plusieurs noms possibles venant du backend.
   */
  private isOwnedByCurrentUser(model: AccessibleModel): boolean {
    const currentUserId = this.getCurrentUserId();
    const currentUserEmail = this.getCurrentUserEmail();

    const ownerId =
      model['ownerId'] ??
      model['projectOwnerId'] ??
      model['projectOwnerUserId'] ??
      model['createdById'] ??
      model['userId'] ??
      model['owner_id'] ??
      model['project_owner_id'];

    const ownerEmail =
      model['ownerEmail'] ??
      model['projectOwnerEmail'] ??
      model['createdByEmail'] ??
      model['userEmail'] ??
      model['owner_email'] ??
      model['project_owner_email'];

    if (currentUserId !== null && currentUserId !== undefined && ownerId !== null && ownerId !== undefined) {
      return Number(ownerId) === Number(currentUserId);
    }

    if (currentUserEmail && ownerEmail) {
      return String(ownerEmail).toLowerCase().trim() === String(currentUserEmail).toLowerCase().trim();
    }

    /*
     * Si le backend ne retourne pas ownerId / ownerEmail,
     * Angular ne peut pas savoir avec certitude si le modèle est au user connecté.
     * Dans ce cas, on garde le modèle PUBLIC_API / SHARED.
     *
     * Pour une correction parfaite, le backend doit retourner ownerId ou projectOwnerId.
     */
    return false;
  }

  private getCurrentUserId(): number | null {
    const user = this.getStoredUser();

    if (user) {
      const id =
        user.id ??
        user.userId ??
        user.user_id ??
        user.currentUserId ??
        user.sub;

      if (id !== null && id !== undefined && !Number.isNaN(Number(id))) {
        return Number(id);
      }
    }

    const tokenPayload = this.getJwtPayload();

    if (tokenPayload) {
      const id =
        tokenPayload.id ??
        tokenPayload.userId ??
        tokenPayload.user_id ??
        tokenPayload.sub;

      if (id !== null && id !== undefined && !Number.isNaN(Number(id))) {
        return Number(id);
      }
    }

    return null;
  }

  private getCurrentUserEmail(): string | null {
    const user = this.getStoredUser();

    if (user) {
      const email =
        user.email ??
        user.username ??
        user.userEmail ??
        user.mail;

      if (email) {
        return String(email).toLowerCase().trim();
      }
    }

    const tokenPayload = this.getJwtPayload();

    if (tokenPayload) {
      const email =
        tokenPayload.email ??
        tokenPayload.username ??
        tokenPayload.sub;

      if (email) {
        return String(email).toLowerCase().trim();
      }
    }

    return null;
  }

  private getStoredUser(): any | null {
    const possibleKeys = [
      'user',
      'currentUser',
      'mlops_user',
      'auth_user',
      'loggedUser'
    ];

    for (const key of possibleKeys) {
      const raw =
        localStorage.getItem(key) ||
        sessionStorage.getItem(key);

      if (!raw) {
        continue;
      }

      try {
        return JSON.parse(raw);
      } catch {
        continue;
      }
    }

    return null;
  }

  private getJwtPayload(): any | null {
    const token =
      sessionStorage.getItem('mlops_token') ||
      localStorage.getItem('mlops_token') ||
      sessionStorage.getItem('token') ||
      localStorage.getItem('token') ||
      sessionStorage.getItem('jwt') ||
      localStorage.getItem('jwt') ||
      sessionStorage.getItem('accessToken') ||
      localStorage.getItem('accessToken') ||
      sessionStorage.getItem('authToken') ||
      localStorage.getItem('authToken');

    if (!token) {
      return null;
    }

    try {
      const cleanToken = token.startsWith('Bearer ')
        ? token.substring(7)
        : token;

      const parts = cleanToken.split('.');

      if (parts.length !== 3) {
        return null;
      }

      const payload = parts[1]
        .replace(/-/g, '+')
        .replace(/_/g, '/');

      const decoded = atob(payload);
      return JSON.parse(decoded);
    } catch {
      return null;
    }
  }

  getExternalEndpoint(model: AccessibleModel): string {
    if (model.externalEndpoint) {
      return model.externalEndpoint;
    }

    return this.modelRegistryService.getExternalPredictionEndpoint(
      model.registeredModelName
    );
  }

  getProjectLabel(model: AccessibleModel): string {
    return model.projectName || `Project ${model.projectId}`;
  }

  getModelVersion(model: AccessibleModel): string {
    const version =
      model?.modelVersion ??
      model?.['model_version'] ??
      model?.productionVersion ??
      model?.['production_version'] ??
      model?.['mlflowProductionVersion'] ??
      '-';

    return version != null && String(version).trim() !== '' ? String(version) : '-';
  }

  getVisibilityLabel(model: AccessibleModel): string {
    switch (model.visibility) {
      case 'PUBLIC_API':
        return 'Public API';
      case 'SHARED':
        return 'Partagé';
      case 'PRIVATE':
        return 'Privé';
      default:
        return model.visibility || 'Non défini';
    }
  }

  getVisibilityClass(model: AccessibleModel): string {
    switch (model.visibility) {
      case 'PUBLIC_API':
        return 'badge-public';
      case 'SHARED':
        return 'badge-shared';
      case 'PRIVATE':
        return 'badge-private';
      default:
        return 'badge-default';
    }
  }

  getAccessDescription(model: AccessibleModel): string {
    switch (model.visibility) {
      case 'PUBLIC_API':
        return 'Ce modèle est exposé via le SMART MODEL GATEWAY. Le propriétaire doit vous fournir une clé API (jamais affichée ici) pour appeler l’endpoint externe.';
      case 'SHARED':
        return 'Ce modèle est partagé avec vous dans la plateforme. L’accès externe utilise une clé API fournie par le propriétaire (X-API-KEY).';
      case 'PRIVATE':
        return 'Ce modèle est privé.';
      default:
        return 'Mode d’accès non défini.';
    }
  }

  copyToClipboard(value?: string | null, label = 'Valeur'): void {
    if (!value) {
      this.errorMessage = `${label} indisponible.`;
      this.successMessage = '';
      return;
    }

    navigator.clipboard
      .writeText(value)
      .then(() => {
        this.successMessage = `${label} copiée avec succès.`;
        this.errorMessage = '';

        setTimeout(() => {
          this.successMessage = '';
        }, 2500);
      })
      .catch((error) => {
        console.error('[AccessibleModels] Clipboard error:', error);
        this.errorMessage = `Impossible de copier ${label}.`;
        this.successMessage = '';
      });
  }

  buildCurlExample(model: AccessibleModel): string {
    const endpoint = this.getExternalEndpoint(model);
    const apiKey = 'VOTRE_CLE_API';

    return `curl.exe -X POST "${endpoint}" \\
  -H "Content-Type: application/json" \\
  -H "X-API-KEY: ${apiKey}" \\
  -d '{
    "feature1": "value",
    "feature2": 123
  }'`;
  }
}