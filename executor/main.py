"""
Entry point for the CodePilot executor service.

Start with:
    uvicorn main:app --reload --port 8001

In K8s (§12.1) this runs as a Deployment behind a ClusterIP Service,
only reachable from the orchestrator — not exposed externally.
"""
from fastapi import FastAPI
from api.routes import router

app = FastAPI(
    title="CodePilot Executor",
    description="Sandboxed Python code action runner for the CodePilot agent platform.",
    version="0.1.0",
)

app.include_router(router)
