#!/bin/bash
set -e

echo "Running post-start setup..."

# Ensure mounted cache directories exist
mkdir -p /home/vscode/.sbt /home/vscode/.ivy2 /home/vscode/.cache/coursier /home/vscode/.claude

# Fix ownership of mounted cache directories (Docker volumes are created as root)
sudo chown -R vscode:vscode /home/vscode/.sbt /home/vscode/.ivy2 /home/vscode/.cache /home/vscode/.claude 2>/dev/null || true

# Ensure Claude Code has bypass permissions configured
if [ ! -f /home/vscode/.claude/settings.json ] || ! grep -q "bypassPermissions" /home/vscode/.claude/settings.json 2>/dev/null; then
  echo "Setting Claude Code to bypass permissions mode..."
  cat > /home/vscode/.claude/settings.json << 'EOF'
{
  "permissions": {
    "defaultMode": "bypassPermissions"
  }
}
EOF
fi

# Configure gh with token if available
if [ -n "$GITHUB_TOKEN" ]; then
  echo "GITHUB_TOKEN is set - gh CLI will use it for authentication."
fi

echo ""
echo "Dev container ready!"
echo "Note: Claude Code is configured to bypass all permissions by default."
