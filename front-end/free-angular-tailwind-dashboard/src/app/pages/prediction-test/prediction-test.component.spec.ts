import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PredictionTestComponent } from './prediction-test.component';

describe('PredictionTestComponent', () => {
  let component: PredictionTestComponent;
  let fixture: ComponentFixture<PredictionTestComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PredictionTestComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(PredictionTestComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
