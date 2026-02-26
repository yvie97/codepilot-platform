FROM python:3.13-slim

# git  — needed to clone repositories into workspaces
# tar   — needed for workspace snapshot / restore
RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        tar \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies first (cached layer — only invalidated when
# requirements.txt changes, not when source code changes)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application source
COPY . .

# Run as a non-root user for security
RUN useradd --system --no-create-home executor
USER executor

EXPOSE 8001

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
