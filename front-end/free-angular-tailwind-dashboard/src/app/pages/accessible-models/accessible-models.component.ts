import { CommonModule } from '@angular/common';
import { Component, OnInit, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, forkJoin, of } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import {
  AccessibleModel,
  ModelApiKeyItem,
  ModelRegistryService
} from '../../core/services/model-registry.service';
import { SmartSearchComponent } from '../../shared/components/common/smart-search/smart-search.component';
import {
  SearchPageResponse,
  SmartSearchParams
} from '../../shared/components/common/smart-search/smart-search.types';

interface ModelWithKey {
  model: AccessibleModel;
  myKey: ModelApiKeyItem | null;
  keyGenerated: boolean;
  generatedFullKey: string | null;
  generating: boolean;
  deleting: boolean;
  keyNameInput: string;
  expiryInput: string;
  showKeyModal: boolean;
}

@Component({
  selector: 'app-accessible-models',
  standalone: true,
  imports: [CommonModule, FormsModule, SmartSearchComponent],
  templateUrl: './accessible-models.component.html',
  styleUrl: './accessible-models.component.css'
})
export class AccessibleModelsComponent implements OnInit {
  @ViewChild(SmartSearchComponent) smartSearch!: SmartSearchComponent<AccessibleModel>;

  models: AccessibleModel[] = [];
  modelsWithKeys: ModelWithKey[] = [];
  totalModels = 0;

  loading = false;
  errorMessage = '';
  successMessage = '';

  constructor(private modelRegistryService: ModelRegistryService) {}

  ngOnInit(): void {}

  loadAccessibleModels(): void {
    this.smartSearch?.resetAndReload();
  }

  searchAccessibleModels = (params: SmartSearchParams): Observable<SearchPageResponse<AccessibleModel>> =>
    this.modelRegistryService.searchDeployments('accessible', params);

  onSearchItems(models: AccessibleModel[]): void {
    const filtered = (models || [])
      .filter((model) => this.isUsableModel(model))
      .filter((model) => this.isAccessibleExternalModel(model));

    this.models = filtered;
    this.loadKeysForModels(filtered);
    this.loading = false;
  }

  onTotalChange(total: number): void {
    this.totalModels = total;
  }

  private loadKeysForModels(models: AccessibleModel[]): void {
    this.modelsWithKeys = models.map((model) => ({
      model,
      myKey: null,
      keyGenerated: false,
      generatedFullKey: null,
      generating: false,
      deleting: false,
      keyNameInput: '',
      expiryInput: '',
      showKeyModal: false
    }));

    const keyRequests = this.modelsWithKeys
      .filter((mwk) => mwk.model.visibility === 'SHARED' || mwk.model.visibility === 'PUBLIC_API')
      .map((mwk) =>
        this.modelRegistryService.getMyApiKey(mwk.model.id ?? 0).pipe(
          tap((key) => { mwk.myKey = key; }),
          catchError(() => { mwk.myKey = null; return of(null); })
        )
      );

    if (keyRequests.length > 0) {
      forkJoin(keyRequests).subscribe();
    }
  }

  isShared(model: AccessibleModel): boolean {
    return model.visibility === 'SHARED';
  }

  isPublicApi(model: AccessibleModel): boolean {
    return model.visibility === 'PUBLIC_API';
  }

  getKeyForModel(model: AccessibleModel): ModelWithKey | undefined {
    return this.modelsWithKeys.find((mwk) => mwk.model === model);
  }

  generateKey(mwk: ModelWithKey): void {
    if (!mwk.model.id || mwk.generating) {
      return;
    }

    mwk.generating = true;
    this.errorMessage = '';

    const body: any = {
      name: mwk.keyNameInput || `cle ${mwk.model.registeredModelName}`
    };

    if (mwk.expiryInput) {
      body.expiresAt = mwk.expiryInput + 'T23:59:59';
    }

    this.modelRegistryService.createApiKey(mwk.model.id, body).pipe(
      tap((response) => {
        mwk.myKey = response;
        mwk.keyGenerated = true;
        mwk.generatedFullKey = response.apiKey || null;
        mwk.generating = false;
        mwk.keyNameInput = '';
        mwk.expiryInput = '';
        mwk.showKeyModal = true;

        this.successMessage = `Cle API generee pour ${mwk.model.registeredModelName}.`;
        setTimeout(() => { this.successMessage = ''; }, 5000);
      }),
      catchError((err) => {
        mwk.generating = false;
        this.errorMessage = `Erreur : ${err?.error?.message || err.message || 'Erreur inconnue'}`;
        return of(null);
      })
    ).subscribe();
  }

  closeKeyModal(mwk: ModelWithKey): void {
    mwk.showKeyModal = false;
    mwk.generatedFullKey = null;
  }

  deleteKey(mwk: ModelWithKey): void {
    if (!mwk.myKey?.id || mwk.deleting) {
      return;
    }

    if (!confirm(`Supprimer definitivement la cle API pour ${mwk.model.registeredModelName} ? Cette action est irreversible.`)) {
      return;
    }

    mwk.deleting = true;

    this.modelRegistryService.deleteApiKey(mwk.myKey.id).pipe(
      tap(() => {
        mwk.myKey = null;
        mwk.keyGenerated = false;
        mwk.generatedFullKey = null;
        mwk.deleting = false;
        this.successMessage = `Cle API supprimee pour ${mwk.model.registeredModelName}.`;
        setTimeout(() => { this.successMessage = ''; }, 3000);
      }),
      catchError((err) => {
        mwk.deleting = false;
        this.errorMessage = `Erreur : ${err?.error?.message || 'Erreur inconnue'}`;
        return of(null);
      })
    ).subscribe();
  }

  formatDateShort(dateStr: string | null | undefined): string {
    if (!dateStr) return '-';
    try {
      const d = new Date(dateStr);
      return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
    } catch {
      return dateStr;
    }
  }

  formatDateTime(dateStr: string | null | undefined): string {
    if (!dateStr) return '-';
    try {
      const d = new Date(dateStr);
      return d.toLocaleDateString('fr-FR', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit'
      });
    } catch {
      return dateStr;
    }
  }

  private isUsableModel(model: AccessibleModel): boolean {
    return (
      !!model &&
      model.deploymentStatus === 'DEPLOYED' &&
      model.active === true &&
      !!model.predictionEndpoint
    );
  }

  private isAccessibleExternalModel(model: AccessibleModel): boolean {
    if (!model) return false;
    if (model.visibility === 'PRIVATE') return false;
    if (model.visibility !== 'PUBLIC_API' && model.visibility !== 'SHARED') return false;
    return !this.isOwnedByCurrentUser(model);
  }

  private isOwnedByCurrentUser(model: AccessibleModel): boolean {
    const currentUserId = this.getCurrentUserId();
    const currentUserEmail = this.getCurrentUserEmail();

    const ownerId =
      model['ownerId'] ?? model['projectOwnerId'] ?? model['projectOwnerUserId'] ??
      model['createdById'] ?? model['userId'] ?? model['owner_id'] ?? model['project_owner_id'];

    const ownerEmail =
      model['ownerEmail'] ?? model['projectOwnerEmail'] ?? model['createdByEmail'] ??
      model['userEmail'] ?? model['owner_email'] ?? model['project_owner_email'];

    if (currentUserId !== null && currentUserId !== undefined && ownerId !== null && ownerId !== undefined) {
      return Number(ownerId) === Number(currentUserId);
    }

    if (currentUserEmail && ownerEmail) {
      return String(ownerEmail).toLowerCase().trim() === String(currentUserEmail).toLowerCase().trim();
    }

    return false;
  }

  private getCurrentUserId(): number | null {
    const user = this.getStoredUser();
    if (user) {
      const id = user.id ?? user.userId ?? user.user_id ?? user.currentUserId ?? user.sub;
      if (id !== null && id !== undefined && !Number.isNaN(Number(id))) {
        return Number(id);
      }
    }
    const tokenPayload = this.getJwtPayload();
    if (tokenPayload) {
      const id = tokenPayload.id ?? tokenPayload.userId ?? tokenPayload.user_id ?? tokenPayload.sub;
      if (id !== null && id !== undefined && !Number.isNaN(Number(id))) {
        return Number(id);
      }
    }
    return null;
  }

  private getCurrentUserEmail(): string | null {
    const user = this.getStoredUser();
    if (user) {
      const email = user.email ?? user.username ?? user.userEmail ?? user.mail;
      if (email) return String(email).toLowerCase().trim();
    }
    const tokenPayload = this.getJwtPayload();
    if (tokenPayload) {
      const email = tokenPayload.email ?? tokenPayload.username ?? tokenPayload.sub;
      if (email) return String(email).toLowerCase().trim();
    }
    return null;
  }

  private getStoredUser(): any | null {
    const possibleKeys = ['mlops_user', 'user', 'currentUser', 'auth_user', 'loggedUser'];
    for (const key of possibleKeys) {
      const raw = localStorage.getItem(key) || sessionStorage.getItem(key);
      if (!raw) continue;
      try { return JSON.parse(raw); } catch { continue; }
    }
    return null;
  }

  private getJwtPayload(): any | null {
    const token =
      sessionStorage.getItem('mlops_token') || localStorage.getItem('mlops_token') ||
      sessionStorage.getItem('token') || localStorage.getItem('token') ||
      sessionStorage.getItem('jwt') || localStorage.getItem('jwt') ||
      sessionStorage.getItem('accessToken') || localStorage.getItem('accessToken') ||
      sessionStorage.getItem('authToken') || localStorage.getItem('authToken');

    if (!token) return null;

    try {
      const cleanToken = token.startsWith('Bearer ') ? token.substring(7) : token;
      const parts = cleanToken.split('.');
      if (parts.length !== 3) return null;
      const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(atob(payload));
    } catch {
      return null;
    }
  }

  getExternalEndpoint(model: AccessibleModel): string {
    if (model.externalEndpoint) return model.externalEndpoint;
    return this.modelRegistryService.getExternalPredictionEndpoint(model.registeredModelName);
  }

  getProjectLabel(model: AccessibleModel): string {
    return model.projectName || `Project ${model.projectId}`;
  }

  getModelVersion(model: AccessibleModel): string {
    const version =
      model?.modelVersion ?? model?.['model_version'] ?? model?.productionVersion ??
      model?.['production_version'] ?? model?.['mlflowProductionVersion'] ?? '-';
    return version != null && String(version).trim() !== '' ? String(version) : '-';
  }

  getVisibilityLabel(model: AccessibleModel): string {
    switch (model.visibility) {
      case 'PUBLIC_API': return 'Public API';
      case 'SHARED': return 'Partage';
      case 'PRIVATE': return 'Prive';
      default: return model.visibility || 'Non defini';
    }
  }

  getVisibilityClass(model: AccessibleModel): string {
    switch (model.visibility) {
      case 'PUBLIC_API': return 'badge-public';
      case 'SHARED': return 'badge-shared';
      case 'PRIVATE': return 'badge-private';
      default: return 'badge-default';
    }
  }

  getAccessDescription(model: AccessibleModel): string {
    switch (model.visibility) {
      case 'PUBLIC_API':
        return 'Ce modele est accessible via le SMART MODEL GATEWAY. Les cles API sont gerees par le proprietaire dans Model Registry.';
      case 'SHARED':
        return 'Ce modele est partage avec vous. Generez votre propre cle API pour l\'utiliser.';
      case 'PRIVATE':
        return 'Ce modele est prive.';
      default:
        return 'Mode d\'acces non defini.';
    }
  }

  copyToClipboard(value?: string | null, label = 'Valeur'): void {
    if (!value) {
      this.errorMessage = `${label} indisponible.`;
      this.successMessage = '';
      return;
    }
    navigator.clipboard.writeText(value).then(() => {
      this.successMessage = `${label} copiee.`;
      this.errorMessage = '';
      setTimeout(() => { this.successMessage = ''; }, 2500);
    }).catch(() => {
      this.errorMessage = `Impossible de copier ${label}.`;
      this.successMessage = '';
    });
  }

  buildCurlExample(model: AccessibleModel, apiKey?: string | null): string {
    const endpoint = this.getExternalEndpoint(model);
    const keyValue = apiKey || 'VOTRE_CLE_API';
    return `curl.exe -X POST "${endpoint}" \\
  -H "Content-Type: application/json" \\
  -H "X-API-KEY: ${keyValue}" \\
  -d '{
    "feature1": "value",
    "feature2": 123
  }'`;
  }
}
