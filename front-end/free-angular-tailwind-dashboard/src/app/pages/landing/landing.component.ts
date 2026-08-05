import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

type Tinted = { tile: string; color: string };

type LifecycleStep = { label: string; icon: string };
type Feature = Tinted & {
  title: string;
  description: string;
  icon: string;
  route: string;
  adminOnly?: boolean;
};
type QuickAction = Tinted & {
  title: string;
  hint: string;
  icon: string;
  route: string;
};

@Component({
  selector: 'app-landing',
  imports: [CommonModule, RouterModule],
  templateUrl: './landing.component.html',
  styleUrl: './landing.component.css',
})
export class LandingComponent {
  readonly firstName: string;

  constructor(authService: AuthService) {
    const user = authService.getStoredUser();
    this.firstName = user?.firstName ?? 'bienvenue';
  }

  readonly lifecycleSteps: LifecycleStep[] = [
    { label: 'Projets', icon: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z' },
    { label: 'Entraînement', icon: 'M13 10V3L4 14h7v7l9-11h-7z' },
    { label: 'Registre', icon: 'M21 7.5l-9-5.25L3 7.5m18 0l-9 5.25m9-5.25v9l-9 5.25M3 7.5l9 5.25M3 7.5v9l9 5.25m0-9v9' },
    { label: 'Monitoring', icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z' },
  ];

  readonly features: Feature[] = [
    {
      title: 'Gestion des projets',
      description: 'Organisez vos jeux de données, pipelines et modèles par projet.',
      icon: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z',
      tile: 'bg-blue-50 dark:bg-blue-500/15',
      color: 'text-blue-600 dark:text-blue-400',
      route: '/my-projects',
    },
    {
      title: 'Upload & Entraînement',
      description: 'Importez datasets et scripts, lancez des runs standard ou conteneurisés.',
      icon: 'M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3v-6',
      tile: 'bg-green-50 dark:bg-green-500/15',
      color: 'text-green-600 dark:text-green-400',
      route: '/upload-training',
    },
    {
      title: 'Suivi des expériences',
      description: 'Trackez, comparez et recherchez vos expériences avec métriques et artefacts.',
      icon: 'M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z',
      tile: 'bg-purple-50 dark:bg-purple-500/15',
      color: 'text-purple-600 dark:text-purple-400',
      route: '/upload-training',
    },
    {
      title: 'Model Registry',
      description: 'Versionnez vos modèles, promouvez les versions et appliquez une gouvernance.',
      icon: 'M21 7.5l-9-5.25L3 7.5m18 0l-9 5.25m9-5.25v9l-9 5.25M3 7.5l9 5.25M3 7.5v9l9 5.25m0-9v9',
      tile: 'bg-indigo-50 dark:bg-indigo-500/15',
      color: 'text-indigo-600 dark:text-indigo-400',
      route: '/model-registry',
    },
    {
      title: 'Déploiement des modèles',
      description: 'Déployez vos modèles en services REST ou batch et suivez leur disponibilité.',
      icon: 'M15.59 14.37a6 6 0 01-5.84 7.38v-4.8m5.84-2.58a14.98 14.98 0 006.16-12.12A14.98 14.98 0 009.631 8.41m5.96 5.96a14.926 14.926 0 01-5.841 2.58m-.119-8.54a6 6 0 00-7.381 5.84h4.8m2.581-5.84a14.927 14.927 0 00-2.58 5.84m2.699 2.7c-.103.021-.207.041-.311.06a15.09 15.09 0 01-2.448-2.448 14.9 14.9 0 01.06-.312m-2.24 2.39a4.493 4.493 0 00-1.757 4.306 4.493 4.493 0 004.306-1.758M16.5 9a1.5 1.5 0 11-3 0 1.5 1.5 0 013 0z',
      tile: 'bg-orange-50 dark:bg-orange-500/15',
      color: 'text-orange-600 dark:text-orange-400',
      route: '/model-deployments',
    },
    {
      title: 'Tests de prédiction',
      description: 'Exécutez des cas de test sur vos endpoints et validez les prédictions.',
      icon: 'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z',
      tile: 'bg-success-50 dark:bg-success-500/15',
      color: 'text-success-600 dark:text-success-400',
      route: '/prediction-test',
    },
    {
      title: 'Monitoring utilisateur',
      description: 'Tableaux de bord dédiés : santé des runs et performances de vos modèles.',
      icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z',
      tile: 'bg-blue-light-50 dark:bg-blue-light-500/15',
      color: 'text-blue-light-600 dark:text-blue-light-400',
      route: '/my-monitoring',
    },
    {
      title: 'Monitoring admin',
      description: 'Vue centralisée de la plateforme : infrastructure, déploiements et alertes.',
      icon: 'M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z',
      tile: 'bg-theme-pink-500/10 dark:bg-theme-pink-500/20',
      color: 'text-theme-pink-500',
      route: '/admin-monitoring',
      adminOnly: true,
    },
    {
      title: 'Automatisation & réentraînement',
      description: 'Configurations intelligentes et réentraînement programmé pour la production.',
      icon: 'M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15',
      tile: 'bg-warning-50 dark:bg-warning-500/15',
      color: 'text-warning-600 dark:text-warning-400',
      route: '/upload-training',
    },
  ];

  readonly quickActions: QuickAction[] = [
    {
      title: 'My Projects',
      hint: 'Gérer vos projets',
      icon: 'M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z',
      tile: 'bg-blue-50 dark:bg-blue-500/15',
      color: 'text-blue-600 dark:text-blue-400',
      route: '/my-projects',
    },
    {
      title: 'Upload & Training',
      hint: 'Importer & entraîner',
      icon: 'M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3v-6',
      tile: 'bg-green-50 dark:bg-green-500/15',
      color: 'text-green-600 dark:text-green-400',
      route: '/upload-training',
    },
    {
      title: 'Experiments',
      hint: 'Suivi & comparaison',
      icon: 'M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z',
      tile: 'bg-purple-50 dark:bg-purple-500/15',
      color: 'text-purple-600 dark:text-purple-400',
      route: '/upload-training',
    },
    {
      title: 'Model Registry',
      hint: 'Versionner vos modèles',
      icon: 'M21 7.5l-9-5.25L3 7.5m18 0l-9 5.25m9-5.25v9l-9 5.25M3 7.5l9 5.25M3 7.5v9l9 5.25m0-9v9',
      tile: 'bg-indigo-50 dark:bg-indigo-500/15',
      color: 'text-indigo-600 dark:text-indigo-400',
      route: '/model-registry',
    },
    {
      title: 'Deployed Models',
      hint: 'Voir vos déploiements',
      icon: 'M15.59 14.37a6 6 0 01-5.84 7.38v-4.8m5.84-2.58a14.98 14.98 0 006.16-12.12A14.98 14.98 0 009.631 8.41m5.96 5.96a14.926 14.926 0 01-5.841 2.58m-.119-8.54a6 6 0 00-7.381 5.84h4.8m2.581-5.84a14.927 14.927 0 00-2.58 5.84m2.699 2.7c-.103.021-.207.041-.311.06a15.09 15.09 0 01-2.448-2.448 14.9 14.9 0 01.06-.312m-2.24 2.39a4.493 4.493 0 00-1.757 4.306 4.493 4.493 0 004.306-1.758M16.5 9a1.5 1.5 0 11-3 0 1.5 1.5 0 013 0z',
      tile: 'bg-orange-50 dark:bg-orange-500/15',
      color: 'text-orange-600 dark:text-orange-400',
      route: '/model-deployments',
    },
    {
      title: 'Prediction Test',
      hint: 'Tester un endpoint',
      icon: 'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z',
      tile: 'bg-success-50 dark:bg-success-500/15',
      color: 'text-success-600 dark:text-success-400',
      route: '/prediction-test',
    },
    {
      title: 'My Monitoring',
      hint: 'Surveiller vos modèles',
      icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z',
      tile: 'bg-blue-light-50 dark:bg-blue-light-500/15',
      color: 'text-blue-light-600 dark:text-blue-light-400',
      route: '/my-monitoring',
    },
    {
      title: 'Accessible Models',
      hint: 'Modèles accessibles',
      icon: 'M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z',
      tile: 'bg-theme-pink-500/10 dark:bg-theme-pink-500/20',
      color: 'text-theme-pink-500',
      route: '/accessible-models',
    },
  ];
}
