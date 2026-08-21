import { Routes } from '@angular/router';

import { EcommerceComponent } from './pages/dashboard/ecommerce/ecommerce.component';
import { LandingComponent } from './pages/landing/landing.component';
import { ProfileComponent } from './pages/profile/profile.component';
import { NotFoundComponent } from './pages/other-page/not-found/not-found.component';
import { AppLayoutComponent } from './shared/layout/app-layout/app-layout.component';
import { AdminMonitoringComponent } from './pages/admin-monitoring/admin-monitoring.component';
import { AccessibleModelsComponent } from './pages/accessible-models/accessible-models.component';
import { UserMonitoringComponent } from './pages/user-monitoring/user-monitoring.component';

import { SignInComponent } from './pages/auth-pages/sign-in/sign-in.component';
import { SignUpComponent } from './pages/auth-pages/sign-up/sign-up.component';
import { VerifyEmailComponent } from './pages/auth-pages/verify-email/verify-email.component';
import { ForgotPasswordComponent } from './pages/auth-pages/forgot-password/forgot-password.component';
import { ResetPasswordComponent } from './pages/auth-pages/reset-password/reset-password.component';

import { MyProjectsComponent } from './pages/my-projects/my-projects.component';
import { UploadTrainingComponent } from './pages/upload-training/upload-training.component';
import { UsersManagementComponent } from './pages/users-management/users-management.component';
import { ModelRegistryComponent } from './pages/model-registry/model-registry.component';
import { PredictionTestComponent } from './pages/prediction-test/prediction-test.component';
import { ModelDeploymentsComponent } from './pages/model-deployments/model-deployments.component';

import { guestGuard } from './core/guards/guest.guard';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';

export const routes: Routes = [
  {
    path: '',
    component: AppLayoutComponent,
    canActivate: [authGuard],
    children: [
      {
        path: '',
        component: LandingComponent,
        pathMatch: 'full',
        title: 'Accueil | MLOps Pilot'
      },
      {
        path: 'dashboard',
        component: EcommerceComponent,
        title: 'Tableau de bord | MLOps Pilot'
      },
      {
        path: 'my-monitoring',
        component: UserMonitoringComponent,
        title: 'My Monitoring | MLOps Pilot'
      },
      {
        path: 'my-projects',
        component: MyProjectsComponent,
        title: 'Mes Projets | MLOps Pilot'
      },
      {
        path: 'upload-training',
        component: UploadTrainingComponent,
        title: 'Upload & Training | MLOps Pilot'
      },
      {
        path: 'model-registry',
        component: ModelRegistryComponent,
        title: 'Model Registry | MLOps Pilot'
      },
      {
        path: 'prediction-test',
        component: PredictionTestComponent,
        title: 'Prediction Test | MLOps Pilot'
      },
      {
        path: 'users-management',
        component: UsersManagementComponent,
        canActivate: [adminGuard],
        title: 'Users Management | MLOps Pilot'
      },
      {
        path: 'admin-monitoring',
        component: AdminMonitoringComponent,
        canActivate: [adminGuard],
        title: 'Admin Monitoring | MLOps Pilot'
      },
      {
        path: 'profile',
        component: ProfileComponent,
        title: 'Profile | MLOps Pilot'
      },
      {
        path: 'accessible-models',
        component: AccessibleModelsComponent,
        title: 'Accessible Models | MLOps Pilot'
      },
      {
        path: 'model-deployments',
        component: ModelDeploymentsComponent,
        title: 'Deployed Models | MLOps Pilot'
      }
    ]
  },
  {
    path: 'signin',
    component: SignInComponent,
    canActivate: [guestGuard],
    title: 'Connexion | MLOps Pilot'
  },
  {
    path: 'signup',
    component: SignUpComponent,
    canActivate: [guestGuard],
    title: 'Inscription | MLOps Pilot'
  },
  {
    path: 'verify-email',
    component: VerifyEmailComponent,
    title: 'Verify Email | MLOps Pilot'
  },
  {
    path: 'forgot-password',
    component: ForgotPasswordComponent,
    title: 'Mot de passe oublié | MLOps Pilot'
  },
  {
    path: 'reset-password',
    component: ResetPasswordComponent,
    title: 'Reset Password | MLOps Pilot'
  },
  {
    path: '**',
    component: NotFoundComponent,
    title: 'Not Found | MLOps Pilot'
  }
];
