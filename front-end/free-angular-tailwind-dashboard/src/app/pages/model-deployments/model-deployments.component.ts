import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, catchError, of } from 'rxjs';

import {
  DeploymentVisibility,
  ModelDeploymentResponse,
  ModelRegistryService,
  UserSearchResult
} from '../../core/services/model-registry.service';
import { SmartSearchComponent } from '../../shared/components/common/smart-search/smart-search.component';
import {
  SearchPageResponse,
  SmartSearchParams
} from '../../shared/components/common/smart-search/smart-search.types';

@Component({
  selector: 'app-model-deployments',
  standalone: true,
  imports: [CommonModule, FormsModule, SmartSearchComponent],
  templateUrl: './model-deployments.component.html',
  styleUrls: ['./model-deployments.component.css']
})
export class ModelDeploymentsComponent implements OnInit, OnDestroy {
  @ViewChild(SmartSearchComponent) smartSearch!: SmartSearchComponent<ModelDeploymentResponse>;

  deployments: ModelDeploymentResponse[] = [];
  totalDeployments = 0;

  loading = false;
  refreshing = false;

  errorMessage = '';
  successMessage = '';

  lastRefreshAt: Date | null = null;

  visibilityDrafts: Record<number, DeploymentVisibility> = {};
  sharedUserSearch: Record<number, string> = {};
  sharedUserResults: Record<number, UserSearchResult[]> = {};
  selectedSharedUsers: Record<number, UserSearchResult[]> = {};

  savingVisibility: Record<number, boolean> = {};
  savingShares: Record<number, boolean> = {};

  private refreshIntervalId: any = null;
  private readonly autoRefreshMs = 30000;

  constructor(private modelRegistryService: ModelRegistryService) {}

  ngOnInit(): void {
    this.loadDeployments(true);

    this.refreshIntervalId = setInterval(() => {
      this.loadDeployments(false);
    }, this.autoRefreshMs);
  }

  ngOnDestroy(): void {
    if (this.refreshIntervalId) {
      clearInterval(this.refreshIntervalId);
      this.refreshIntervalId = null;
    }
  }

  loadDeployments(showLoader = true): void {
    if (showLoader) {
      this.loading = true;
    } else {
      this.refreshing = true;
    }

    this.errorMessage = '';
    this.smartSearch?.reload();
  }

  searchDeployments = (params: SmartSearchParams): Observable<SearchPageResponse<ModelDeploymentResponse>> =>
    this.modelRegistryService.searchDeployments('owned', params);

  onSearchItems(deployments: ModelDeploymentResponse[]): void {
    const cleanDeployments = (deployments || [])
      .filter((deployment) => this.isVisibleAndReallyDeployed(deployment))
      .sort((a, b) => this.compareDeploymentsForDisplay(a, b));

    this.deployments = cleanDeployments;

    for (const deployment of this.deployments) {
      this.initVisibilityDraft(deployment);

      if (this.getVisibility(deployment) === 'SHARED') {
        this.loadDeploymentShares(deployment);
      }
    }

    this.lastRefreshAt = new Date();
    this.loading = false;
    this.refreshing = false;
  }

  onTotalChange(total: number): void {
    this.totalDeployments = total;
  }

  refresh(): void {
    this.smartSearch?.resetAndReload();
  }

  private isVisibleAndReallyDeployed(model: ModelDeploymentResponse): boolean {
    if (!model) {
      return false;
    }

    const status = String(model.deploymentStatus || '').toUpperCase();

    return status === 'DEPLOYED'
      && model.active === true
      && !!model.deploymentName
      && !!model.serviceName;
  }

  private compareDeploymentsForDisplay(
    a: ModelDeploymentResponse,
    b: ModelDeploymentResponse
  ): number {
    const aTime = this.toTime(a.updatedAt || a.deployedAt || a.createdAt);
    const bTime = this.toTime(b.updatedAt || b.deployedAt || b.createdAt);

    if (aTime !== bTime) {
      return bTime - aTime;
    }

    return Number(b.id || 0) - Number(a.id || 0);
  }

  private toTime(value?: string | null): number {
    if (!value) {
      return 0;
    }

    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? 0 : date.getTime();
  }

  getDeploymentId(model: any): number | null {
    const id =
      model?.activeDeploymentId ??
      model?.deploymentId ??
      model?.id ??
      null;

    if (id === null || id === undefined || Number(id) <= 0) {
      return null;
    }

    return Number(id);
  }

  getDeploymentKey(model: any): number {
    return this.getDeploymentId(model) || Number(model?.projectId || 0);
  }

  private initVisibilityDraft(model: ModelDeploymentResponse): void {
    const key = this.getDeploymentKey(model);

    if (!key) {
      return;
    }

    this.visibilityDrafts[key] = this.getVisibility(model);
  }

  trackDeployment(index: number, model: ModelDeploymentResponse): string {
    const deploymentId = this.getDeploymentId(model);
    return `${deploymentId || index}-${model?.registeredModelName || index}`;
  }

  isDeploymentDeployed(model: ModelDeploymentResponse): boolean {
    const status = model?.deploymentStatus || model?.['deployment_status'];
    return String(status || '').toUpperCase() === 'DEPLOYED'
      && model?.active === true;
  }

  isDeploymentFailed(model: ModelDeploymentResponse): boolean {
    const status = model?.deploymentStatus || model?.['deployment_status'];
    return String(status || '').toUpperCase() === 'FAILED';
  }

  isDeploymentDeploying(model: ModelDeploymentResponse): boolean {
    const status = model?.deploymentStatus || model?.['deployment_status'];
    const clean = String(status || '').toUpperCase();

    return clean === 'DEPLOYING' || clean === 'PENDING' || clean === 'BUILDING_IMAGE';
  }

  isDeploymentOutOfSync(model: ModelDeploymentResponse): boolean {
    const status = model?.deploymentStatus || model?.['deployment_status'];
    return String(status || '').toUpperCase() === 'OUT_OF_SYNC';
  }

  getDeploymentLabel(model: ModelDeploymentResponse): string {
    const status = model?.deploymentStatus || model?.['deployment_status'];

    if (!status) {
      return 'NOT DEPLOYED';
    }

    return String(status).toUpperCase();
  }

  getDeploymentBadgeClass(model: ModelDeploymentResponse): string {
    if (this.isDeploymentDeployed(model)) {
      return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300';
    }

    if (this.isDeploymentFailed(model)) {
      return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300';
    }

    if (this.isDeploymentOutOfSync(model)) {
      return 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300';
    }

    if (this.isDeploymentDeploying(model)) {
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300';
    }

    return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300';
  }

  getVisibility(model: any): DeploymentVisibility {
    const raw =
      model?.visibility ??
      model?.deploymentVisibility ??
      model?.deployment_visibility ??
      'PRIVATE';

    const clean = String(raw || 'PRIVATE').trim().toUpperCase();

    if (clean === 'PUBLIC_API') {
      return 'PUBLIC_API';
    }

    if (clean === 'SHARED') {
      return 'SHARED';
    }

    return 'PRIVATE';
  }

  getVisibilityLabel(model: any): string {
    const visibility = this.getVisibility(model);

    if (visibility === 'PUBLIC_API') {
      return 'PUBLIC_API';
    }

    if (visibility === 'SHARED') {
      return 'SHARED';
    }

    return 'PRIVATE';
  }

  getVisibilityBadgeClass(model: any): string {
    const visibility = this.getVisibility(model);

    if (visibility === 'PUBLIC_API') {
      return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300';
    }

    if (visibility === 'SHARED') {
      return 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300';
    }

    return 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300';
  }

  getApiKey(model: any): string {
    return String(model?.apiKey || model?.api_key || '').trim();
  }

  maskApiKey(apiKey: string | null | undefined): string {
    const clean = String(apiKey || '').trim();

    if (!clean) {
      return 'Clé API non disponible';
    }

    if (clean.length <= 14) {
      return clean;
    }

    return `${clean.slice(0, 10)}**********${clean.slice(-6)}`;
  }

  getExternalApiEndpoint(model: ModelDeploymentResponse): string {
    return this.modelRegistryService.getExternalPredictionEndpoint(
      model?.registeredModelName || ''
    );
  }

  getProjectLabel(model: ModelDeploymentResponse): string {
    return String(
      model?.projectName ||
      model?.['projectName'] ||
      model?.projectId ||
      '-'
    );
  }

  getProductionVersion(model: ModelDeploymentResponse): string {
    return String(
      model?.productionVersion ||
      model?.mlflowProductionVersion ||
      model?.modelVersion ||
      '-'
    );
  }

  searchUsersForShare(model: any): void {
    const deploymentId = this.getDeploymentId(model);

    if (!deploymentId) {
      return;
    }

    const query = String(this.sharedUserSearch[deploymentId] || '').trim();

    if (query.length < 2) {
      this.sharedUserResults[deploymentId] = [];
      return;
    }

    this.modelRegistryService.searchUsers(query, deploymentId).subscribe({
      next: (users) => {
        this.sharedUserResults[deploymentId] = users || [];
      },
      error: (error) => {
        console.error('[ModelDeployments] user search error', error);
        this.sharedUserResults[deploymentId] = [];
      }
    });
  }

  onVisibilitySelectChange(model: any, event: Event): void {
    const select = event.target as HTMLSelectElement;
    const newVisibility = select.value as DeploymentVisibility;

    const deploymentId = this.getDeploymentId(model);

    if (!deploymentId) {
      this.errorMessage = 'ID du déploiement introuvable.';
      return;
    }

    const key = this.getDeploymentKey(model);
    const oldVisibility = this.getVisibility(model);

    this.visibilityDrafts[key] = newVisibility;
    this.savingVisibility[deploymentId] = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.modelRegistryService.updateDeploymentVisibility(deploymentId, newVisibility).subscribe({
      next: (updatedDeployment) => {
        this.savingVisibility[deploymentId] = false;

        this.deployments = this.deployments.map((item) => {
          const itemDeploymentId = this.getDeploymentId(item);

          if (itemDeploymentId === deploymentId) {
            this.initVisibilityDraft(updatedDeployment);

            if (this.getVisibility(updatedDeployment) === 'SHARED') {
              this.loadDeploymentShares(updatedDeployment);
            }

            return updatedDeployment;
          }

          return item;
        });

        this.successMessage =
          newVisibility === 'PRIVATE'
            ? 'Le modèle est maintenant privé.'
            : newVisibility === 'PUBLIC_API'
              ? 'Le modèle est maintenant accessible via API publique.'
              : 'Le modèle est maintenant partagé.';

        this.clearSuccessAfterDelay();
        this.loadDeployments(false);
      },
      error: (error) => {
        this.savingVisibility[deploymentId] = false;
        this.visibilityDrafts[key] = oldVisibility;

        this.errorMessage = this.extractErrorMessage(
          error,
          'Erreur lors du changement du mode d’accès.'
        );

        this.loadDeployments(false);
      }
    });
  }

  onSharedUserSelected(model: any, event: Event): void {
    const deploymentId = this.getDeploymentId(model);

    if (!deploymentId) {
      return;
    }

    const select = event.target as HTMLSelectElement;
    const selectedUserId = Number(select.value);

    if (!selectedUserId) {
      return;
    }

    const users = this.sharedUserResults[deploymentId] || [];
    const selectedUser = users.find((user) => Number(user.id) === selectedUserId);

    if (!selectedUser) {
      return;
    }

    this.addSharedUser(model, selectedUser);
    select.value = '';
  }

  addSharedUser(model: any, user: UserSearchResult): void {
    const deploymentId = this.getDeploymentId(model);

    if (!deploymentId || !user?.id) {
      return;
    }

    const currentUsers = this.selectedSharedUsers[deploymentId] || [];
    const alreadyExists = currentUsers.some((item) => Number(item.id) === Number(user.id));

    if (alreadyExists) {
      return;
    }

    this.selectedSharedUsers[deploymentId] = [...currentUsers, user];
  }

  removeSharedUser(model: any, user: UserSearchResult): void {
    const deploymentId = this.getDeploymentId(model);

    if (!deploymentId || !user?.id) {
      return;
    }

    this.selectedSharedUsers[deploymentId] = (this.selectedSharedUsers[deploymentId] || [])
      .filter((item) => Number(item.id) !== Number(user.id));
  }

  saveSharedUsers(model: any): void {
    const deploymentId = this.getDeploymentId(model);

    if (!deploymentId) {
      this.errorMessage = 'ID du déploiement introuvable.';
      return;
    }

    const users = this.selectedSharedUsers[deploymentId] || [];
    const userIds = users
      .map((user) => Number(user.id))
      .filter((id) => !!id);

    this.savingShares[deploymentId] = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.modelRegistryService.shareDeploymentWithUsers(deploymentId, userIds).subscribe({
      next: () => {
        this.savingShares[deploymentId] = false;
        this.successMessage = 'Utilisateurs autorisés enregistrés avec succès.';
        this.clearSuccessAfterDelay();

        this.loadDeploymentShares(model);
        this.loadDeployments(false);
      },
      error: (error) => {
        this.savingShares[deploymentId] = false;
        this.errorMessage = this.extractErrorMessage(
          error,
          'Erreur lors de l’enregistrement des utilisateurs autorisés.'
        );
      }
    });
  }

  loadDeploymentShares(model: any): void {
    const deploymentId = this.getDeploymentId(model);

    if (!deploymentId) {
      return;
    }

    this.modelRegistryService.getDeploymentShares(deploymentId)
      .pipe(catchError(() => of([])))
      .subscribe((shares) => {
        this.selectedSharedUsers[deploymentId] = (shares || [])
          .map((share: any) => ({
            id: share.userId || share.sharedWithUserId,
            email: share.email,
            username: share.username,
            name: share.name,
            fullName: share.fullName || share.name || share.email
          }))
          .filter((user) => !!user.id);
      });
  }

  getUserDisplayName(user: any): string {
    if (!user) {
      return '-';
    }

    const fullName =
      user.fullName ||
      user.name ||
      `${user.firstName || user.prenom || ''} ${user.lastName || user.nom || ''}`.trim();

    if (fullName && user.email) {
      return `${fullName} — ${user.email}`;
    }

    return fullName || user.email || `Utilisateur #${user.id}`;
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

  private clearSuccessAfterDelay(): void {
    setTimeout(() => {
      this.successMessage = '';
    }, 2500);
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