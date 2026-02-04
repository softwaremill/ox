#!/bin/bash
set -e

echo "Running post-start setup..."

# Ensure mounted cache directories exist
mkdir -p /home/vscode/.sbt /home/vscode/.ivy2 /home/vscode/.cache/coursier /home/vscode/.claude

# Fix ownership of mounted cache directories (Docker volumes are created as root)
sudo chown -R vscode:vscode /home/vscode/.sbt /home/vscode/.ivy2 /home/vscode/.cache /home/vscode/.claude 2>/dev/null || true

# Ensure Claude Code settings are configured (bypass permissions + opus model)
echo "Configuring Claude Code settings..."
cat > /home/vscode/.claude/settings.json << 'EOF'
{
  "permissions": {
    "defaultMode": "bypassPermissions"
  },
  "model": "opus"
}
EOF

# Configure gh with token if available
if [ -n "$GITHUB_TOKEN" ]; then
  echo "GITHUB_TOKEN is set - gh CLI will use it for authentication."
fi

echo ""
echo "Dev container ready!"
echo "Note: Claude Code is configured to use Opus model with bypass permissions."
