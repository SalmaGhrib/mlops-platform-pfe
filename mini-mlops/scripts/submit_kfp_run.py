from kfp import Client
from pathlib import Path

p = Path('kubeflow/compiled/custom_model_pipeline.yaml')
config_path = Path('uploaded_configs/02ebc6aa-318f-43cc-86dc-0afdd5dadaf1.yaml')
config = config_path.read_text(encoding='utf-8')

print('Submitting pipeline...')
client = Client()
run = client.create_run_from_pipeline_package(str(p), arguments={'config_yaml': config})
print('Run submitted: ', getattr(run, 'run_id', repr(run)))
