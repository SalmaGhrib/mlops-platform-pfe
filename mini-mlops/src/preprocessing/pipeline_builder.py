from sklearn.pipeline import Pipeline
from src.preprocessing.preprocess import build_preprocessor

def build_pipeline(model, X_train):
    preprocessor = build_preprocessor(X_train)

    pipeline = Pipeline(steps=[
        ("preprocessor", preprocessor),
        ("model", model)
    ])

    return pipeline