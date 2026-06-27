import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ModelRegistryComponent } from './model-registry.component';

describe('ModelRegistryComponent', () => {
  let component: ModelRegistryComponent;
  let fixture: ComponentFixture<ModelRegistryComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ModelRegistryComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ModelRegistryComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
