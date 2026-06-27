import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AccessibleModelsComponent } from './accessible-models.component';

describe('AccessibleModelsComponent', () => {
  let component: AccessibleModelsComponent;
  let fixture: ComponentFixture<AccessibleModelsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AccessibleModelsComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(AccessibleModelsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
