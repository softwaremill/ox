{
  description = "Python shell flake";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }: {

    devShell.x86_64-linux = let
      pkgs = nixpkgs.legacyPackages.x86_64-linux;
      python = pkgs.python3.withPackages (ps: with ps; [
        pip
      ]);
    in
    pkgs.mkShell {
      buildInputs = [ python ];

      shellHook = ''
        # Create a Python virtual environment and activate it
        python -m venv .env
        source .env/bin/activate

        # Install the Python dependencies from requirements.txt
        if [ -f requirements.txt ]; then
          pip install -r requirements.txt
        fi
      '';
    };
  };
}

