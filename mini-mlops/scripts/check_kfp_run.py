from kfp import Client
run_id = 'ca446552-c469-4c31-8219-1445463304ec'
client = Client()
run = client.get_run(run_id)
print('raw run object repr:')
print(repr(run))
print('\nrun object dir:')
print(dir(run))

# Try common access patterns for status
status = None
try:
    status = run.run.status
except Exception:
    try:
        status = run.status
    except Exception:
        try:
            status = run._run.status
        except Exception:
            status = None

print('\nresolved status:', status)

wf = None
for attr in ('pipeline_runtime', 'pipelineRuntime', 'pipeline_runtime_v1', 'pipeline_runtime_v2'):
    try:
        pr = getattr(run, attr)
        wf = getattr(pr, 'workflow_manifest', None)
        if wf:
            break
    except Exception:
        continue

if not wf:
    # try nested
    try:
        wf = run.run.pipeline_runtime.workflow_manifest
    except Exception:
        pass

if wf:
    print('\n--- workflow_manifest (first 4000 chars) ---')
    print(wf[:4000])
else:
    print('\nNo workflow manifest attached to run metadata (or inaccessible).')

print('\nRun script end')
