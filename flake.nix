{
  description = "A Flake to manage multiple modules, and configure all of the OS tools accordingly.";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
  };

  outputs = { self, nixpkgs }: 
  let 
    system = "x86_64-linux";
    pkgs = nixpkgs.legacyPackages.${system};

    # Backend dependencies (Python + FastAPI + OpenAI SDK)
    softwareDeps = with pkgs; [
        pkgs.gtkwave
        pkgs.iverilog
    ];

  in {
    devShells = {
      "${system}" = {
        default = pkgs.mkShell {
          buildInputs = softwareDeps;
        };
      };
    };
  };
}
