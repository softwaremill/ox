#!/bin/bash
set -e

echo "Running post-start setup..."

# Ensure mounted cache directories exist
mkdir -p /home/vscode/.sbt /home/vscode/.ivy2 /home/vscode/.cache/coursier /home/vscode/.claude

# Fix ownership of mounted cache directories (Docker volumes are created as root)
sudo chown -R vscode:vscode /home/vscode/.sbt /home/vscode/.ivy2 /home/vscode/.cache /home/vscode/.claude 2>/dev/null || true

# Update Claude CLI (requires sudo for system installation)
sudo claude update 2>/dev/null || true

# Configure gh with token if available
if [ -n "$GITHUB_TOKEN" ]; then
  echo "GITHUB_TOKEN is set - gh CLI will use it for authentication."
fi

echo ""
echo "Dev container ready!"
