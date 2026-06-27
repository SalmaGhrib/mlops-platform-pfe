import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import {
  AccessibleModel,
  ModelRegistryService
} from '../../core/services/model-registry.service';

@Component({
  selector: 'app-accessible-models',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './accessible-models.component.html',
  styleUrl: './accessible-models.component.css'
})
export class AccessibleModelsComponent implements OnInit {
  models: AccessibleModel[] = [];

  loading = false;
  errorMessage = '';
  successMessage = '';

  constructor(private modelRegistryService: ModelRegistryService) {}

  ngOnInit(): void {
    this.loadAccessibleModels();
  }

  loadAccessibleModels(): void {
    this.loading = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.modelRegistryService.getAccessibleModels().subscribe({
      next: (models) => {
        this.models = (models || [])
          .filter((model) => this.isUsableModel(model))
          .filter((model) => this.isAccessibleExternalModel(model));

        this.loading = false;
      },
      error: (error) => {
        console.error('[AccessibleModels] Load error:', error);
        this.errorMessage =
          "Impossible de charger les modèles accessibles. Vérifiez que l'endpoint backend existe et que l'utilisateur est connecté.";
        this.loading = false;
      }
    });
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
        return 'Ce modèle est public via API. Toute application possédant la clé API peut appeler l’endpoint externe.';
      case 'SHARED':
        return 'Ce modèle est partagé avec vous. Vous êtes autorisé à l’utiliser.';
      case 'PRIVATE':
        return 'Ce modèle est privé.';
      default:
        return 'Mode d’accès non défini.';
    }
  }

  maskApiKey(apiKey?: string | null): string {
    if (!apiKey) {
      return 'Aucune clé API disponible';
    }

    if (apiKey.length <= 14) {
      return apiKey;
    }

    return `${apiKey.substring(0, 8)}********${apiKey.substring(apiKey.length - 5)}`;
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
    const apiKey = model.apiKey || 'VOTRE_CLE_API';

    return `curl -X POST "${endpoint}" \\
  -H "Content-Type: application/json" \\
  -H "X-API-KEY: ${apiKey}" \\
  -d '{
    "feature1": "value",
    "feature2": 123
  }'`;
  }
}