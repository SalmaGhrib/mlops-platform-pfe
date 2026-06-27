import { Routes } from '@angular/router';

import { EcommerceComponent } from './pages/dashboard/ecommerce/ecommerce.component';
import { ProfileComponent } from './pages/profile/profile.component';
import { FormElementsComponent } from './pages/forms/form-elements/form-elements.component';
import { BasicTablesComponent } from './pages/tables/basic-tables/basic-tables.component';
import { BlankComponent } from './pages/blank/blank.component';
import { NotFoundComponent } from './pages/other-page/not-found/not-found.component';
import { AppLayoutComponent } from './shared/layout/app-layout/app-layout.component';
import { InvoicesComponent } from './pages/invoices/invoices.component';
import { AdminMonitoringComponent } from './pages/admin-monitoring/admin-monitoring.component';
import { LineChartComponent } from './pages/charts/line-chart/line-chart.component';
import { BarChartComponent } from './pages/charts/bar-chart/bar-chart.component';
import { AlertsComponent } from './pages/ui-elements/alerts/alerts.component';
import { AvatarElementComponent } from './pages/ui-elements/avatar-element/avatar-element.component';
import { BadgesComponent } from './pages/ui-elements/badges/badges.component';
import { ButtonsComponent } from './pages/ui-elements/buttons/buttons.component';
import { ImagesComponent } from './pages/ui-elements/images/images.component';
import { VideosComponent } from './pages/ui-elements/videos/videos.component';
import { AccessibleModelsComponent } from './pages/accessible-models/accessible-models.component';

import { SignInComponent } from './pages/auth-pages/sign-in/sign-in.component';
import { SignUpComponent } from './pages/auth-pages/sign-up/sign-up.component';
import { VerifyEmailComponent } from './pages/auth-pages/verify-email/verify-email.component';
import { ForgotPasswordComponent } from './pages/auth-pages/forgot-password/forgot-password.component';
import { ResetPasswordComponent } from './pages/auth-pages/reset-password/reset-password.component';

import { CalenderComponent } from './pages/calender/calender.component';
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
        component: EcommerceComponent,
        pathMatch: 'full',
        title: 'Dashboard | MLOps Platform'
      },
      {
        path: 'calendar',
        component: CalenderComponent,
        title: 'Calendar | MLOps Platform'
      },
      {
        path: 'my-projects',
        component: MyProjectsComponent,
        title: 'Mes Projets | MLOps Platform'
      },
      {
        path: 'upload-training',
        component: UploadTrainingComponent,
        title: 'Upload & Training | MLOps Platform'
      },
      {
        path: 'model-registry',
        component: ModelRegistryComponent,
        title: 'Model Registry | MLOps Platform'
      },
      
      {
        path: 'prediction-test',
        component: PredictionTestComponent,
        title: 'Prediction Test | MLOps Platform'
      },
      {
        path: 'users-management',
        component: UsersManagementComponent,
        canActivate: [adminGuard],
        title: 'Users Management | MLOps Platform'
      },
      {
  path: 'admin-monitoring',
  component: AdminMonitoringComponent,
  canActivate: [adminGuard],
  title: 'Admin Monitoring | MLOps Platform'
},
      {
        path: 'profile',
        component: ProfileComponent,
        title: 'Profile | MLOps Platform'
      },
      {
  path: 'accessible-models',
  component: AccessibleModelsComponent,
  title: 'Accessible Models | MLOps Platform'
},
      {
        path: 'form-elements',
        component: FormElementsComponent,
        title: 'Form Elements | MLOps Platform'
      },
      {
  path: 'model-deployments',
  component: ModelDeploymentsComponent,
  title: 'Deployed Models | MLOps Platform'
},
      {
        path: 'basic-tables',
        component: BasicTablesComponent,
        title: 'Tables | MLOps Platform'
      },
      {
        path: 'blank',
        component: BlankComponent,
        title: 'Blank | MLOps Platform'
      },
      {
        path: 'invoice',
        component: InvoicesComponent,
        title: 'Invoice | MLOps Platform'
      },
      {
        path: 'line-chart',
        component: LineChartComponent,
        title: 'Line Chart | MLOps Platform'
      },
      {
        path: 'bar-chart',
        component: BarChartComponent,
        title: 'Bar Chart | MLOps Platform'
      },
      {
        path: 'alerts',
        component: AlertsComponent,
        title: 'Alerts | MLOps Platform'
      },
      {
        path: 'avatars',
        component: AvatarElementComponent,
        title: 'Avatars | MLOps Platform'
      },
      {
        path: 'badge',
        component: BadgesComponent,
        title: 'Badges | MLOps Platform'
      },
      {
        path: 'buttons',
        component: ButtonsComponent,
        title: 'Buttons | MLOps Platform'
      },
      {
        path: 'images',
        component: ImagesComponent,
        title: 'Images | MLOps Platform'
      },
      {
        path: 'videos',
        component: VideosComponent,
        title: 'Videos | MLOps Platform'
      }
    ]
  },

  {
    path: 'signin',
    component: SignInComponent,
    canActivate: [guestGuard],
    title: 'Sign In | MLOps Platform'
  },
  {
    path: 'signup',
    component: SignUpComponent,
    canActivate: [guestGuard],
    title: 'Sign Up | MLOps Platform'
  },
  {
    path: 'verify-email',
    component: VerifyEmailComponent,
    title: 'Verify Email | MLOps Platform'
  },
  {
    path: 'forgot-password',
    component: ForgotPasswordComponent,
    title: 'Forgot Password | MLOps Platform'
  },
  {
    path: 'reset-password',
    component: ResetPasswordComponent,
    title: 'Reset Password | MLOps Platform'
  },
  {
    path: '**',
    component: NotFoundComponent,
    title: 'Not Found | MLOps Platform'
  }
];