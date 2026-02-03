#!/bin/bash
set -e

echo "Finalizing dev container setup..."

# Fix ownership of mounted cache directories (Docker volumes are created as root)
echo "Fixing permissions on mounted cache directories..."
sudo chown -R vscode:vscode /home/vscode/.sbt /home/vscode/.ivy2 /home/vscode/.cache /home/vscode/.claude 2>/dev/null || true

# Override git config with environment variables if provided
if [ -n "$GIT_USER_NAME" ]; then
  git config --global user.name "$GIT_USER_NAME"
fi
if [ -n "$GIT_USER_EMAIL" ]; then
  git config --global user.email "$GIT_USER_EMAIL"
fi

# Configure gh with token if available
if [ -n "$GITHUB_TOKEN" ]; then
  echo "Configuring GitHub CLI..."
  echo "GITHUB_TOKEN is set - gh CLI will use it for authentication."
  gh auth status
else
  echo "Warning: GITHUB_TOKEN not set. GitHub CLI will not be authenticated."
fi

# Disable SSH agent forwarding explicitly
unset SSH_AUTH_SOCK
unset SSH_AGENT_PID

# Verify Claude Code API key is available
if [ -n "$ANTHROPIC_API_KEY" ]; then
  echo "ANTHROPIC_API_KEY is set - Claude Code will use it for authentication."
else
  echo "Warning: ANTHROPIC_API_KEY not set. Claude Code will require manual authentication."
fi

echo ""
echo "Dev container setup complete!"
echo "Java version: $(java -version 2>&1 | head -n 1)"
echo "sbt version: $(sbt --version 2>&1 | grep 'sbt version')"
echo "gh version: $(gh --version | head -n 1)"
echo ""
echo "Note: Claude Code is configured to bypass all permissions by default."
