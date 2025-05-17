{
  description = "A Flake to manage multiple modules and configure OS tools (Chisel, CIRCT, firtool, etc.)";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
  };

  outputs = { self, nixpkgs }:
  let
    system = "x86_64-linux";
    overlays = [ (import ./nix/overlay.nix) ];
    pkgs = import nixpkgs {
      inherit system;
      overlays = overlays;
    };

    # Define your combined development dependencies.
    softwareDeps = with pkgs; [
      gtkwave
      verilator
      zulu8
      scala
      sbt
      coursier
      circt-full
      valgrind
    ];
  in {
    devShells = {
      "${system}" = {
        default = pkgs.mkShell {
          buildInputs = softwareDeps;
           # Set up the Ivy cache by creating a directory and exporting IVY_HOME.
          shellHook = ''
            export IVY_HOME=$PWD/.ivy2
            mkdir -p $IVY_HOME
          '';
        };
      };
    };

  };
}
