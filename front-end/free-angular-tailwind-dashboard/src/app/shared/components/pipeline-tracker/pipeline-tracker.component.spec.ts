import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import {
  ExperimentService,
  MlExperimentResponse,
  PipelineStatusResponse
} from '../../../core/services/experiment.service';
import { PipelineTrackerComponent } from './pipeline-tracker.component';

describe('PipelineTrackerComponent', () => {
  let component: PipelineTrackerComponent;
  let fixture: ComponentFixture<PipelineTrackerComponent>;
  let experimentServiceSpy: jasmine.SpyObj<ExperimentService>;

  const baseExperiment: MlExperimentResponse = {
    id: 1,
    projectId: 1,
    projectName: 'Projet test',
    experimentName: 'Run test',
    status: 'RUNNING',
    modelName: 'model',
    modelType: 'classification',
    primaryMetric: 'accuracy',
    primaryMetricValue: null,
    direction: 'maximize',
    modelFileName: 'train.py',
    datasetFileName: 'dataset.csv',
    registeredModelName: '',
    modelVersion: null,
    globalScore: null,
    message: '',
    errorMessage: '',
    createdAt: '',
    runtimeMode: 'STANDARD_RUNTIME'
  };

  const customPipeline: PipelineStatusResponse = {
    experimentId: 1,
    runtimeMode: 'CUSTOM_DOCKERFILE',
    runStatus: 'RUNNING',
    buildStatus: 'IMAGE_BUILD_RUNNING',
    globalProgress: 40,
    updatedAt: '2026-08-11T00:00:00Z',
    steps: [
      {
        stepCode: 'FILES_VALIDATED',
        label: 'Validation',
        status: 'SUCCEEDED',
        progress: 100,
        orderIndex: 0,
        startedAt: '2026-08-11T00:00:00Z',
        completedAt: '2026-08-11T00:00:05Z',
        hasLogs: false
      },
      {
        stepCode: 'IMAGE_BUILD_STARTED',
        label: 'Build image (Kaniko)',
        status: 'RUNNING',
        progress: 50,
        orderIndex: 1,
        startedAt: '2026-08-11T00:00:05Z',
        jobName: 'mlops-build-abc',
        hasLogs: true
      },
      {
        stepCode: 'TRAINING_RUNNING',
        label: 'Entraînement',
        status: 'PENDING',
        orderIndex: 2
      }
    ]
  };

  beforeEach(async () => {
    experimentServiceSpy = jasmine.createSpyObj('ExperimentService', [
      'getPipelineStepLogs'
    ]);
    experimentServiceSpy.getPipelineStepLogs.and.returnValue(of('log line 1\nlog line 2'));

    await TestBed.configureTestingModule({
      imports: [PipelineTrackerComponent],
      providers: [
        { provide: ExperimentService, useValue: experimentServiceSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineTrackerComponent);
    component = fixture.componentInstance;
    component.experiment = { ...baseExperiment };
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should use backend steps when pipeline is provided', () => {
    component.pipeline = customPipeline;
    fixture.detectChanges();

    expect(component.usesBackendSteps).toBeTrue();
    expect(component.steps.length).toBe(3);
    expect(component.steps[0].state).toBe('done');
    expect(component.steps[1].state).toBe('active');
    expect(component.steps[2].state).toBe('pending');
    expect(component.steps[1].jobName).toBe('mlops-build-abc');
    expect(component.steps[1].hasLogs).toBeTrue();
  });

  it('should expose backend global progress', () => {
    component.pipeline = customPipeline;
    fixture.detectChanges();

    expect(component.progressPercent).toBe(40);
  });

  it('should fall back to heuristic steps without pipeline', () => {
    component.pipeline = null;
    fixture.detectChanges();

    expect(component.usesBackendSteps).toBeFalse();
    expect(component.steps.length).toBeGreaterThan(0);
  });

  it('should detect custom runtime mode from experiment', () => {
    component.experiment = { ...baseExperiment, runtimeMode: 'CUSTOM_DOCKERFILE' };
    fixture.detectChanges();

    expect(component.isDockerMode).toBeTrue();
  });

  it('should map SKIPPED backend steps to skipped state', () => {
    component.pipeline = {
      ...customPipeline,
      steps: [
        {
          stepCode: 'ACR_PUSH_COMPLETED',
          status: 'SKIPPED',
          orderIndex: 0
        }
      ]
    };
    fixture.detectChanges();

    expect(component.steps[0].state).toBe('skipped');
  });

  it('should load step logs and toggle them', () => {
    component.pipeline = customPipeline;
    fixture.detectChanges();

    const step = component.steps[1];
    component.loadStepLogs(step);

    expect(experimentServiceSpy.getPipelineStepLogs).toHaveBeenCalledWith(1, 'IMAGE_BUILD_STARTED');
    expect(component.hasLoadedLogs(step)).toBeTrue();
    expect(component.stepLogs['IMAGE_BUILD_STARTED']).toBe('log line 1\nlog line 2');

    component.loadStepLogs(step);
    expect(component.hasLoadedLogs(step)).toBeFalse();
  });

  it('should compute duration between startedAt and completedAt', () => {
    component.pipeline = customPipeline;
    fixture.detectChanges();

    expect(component.formatDuration('2026-08-11T00:00:00Z', '2026-08-11T00:00:05Z')).toBe('5s');
  });
});
