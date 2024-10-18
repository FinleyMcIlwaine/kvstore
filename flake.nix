{
  description = "Nix flake";

  inputs = { nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.05"; };

  outputs = { self, nixpkgs }:
    let
      javaVersion = 17;

      overlays = [
        (final: prev: rec {
          jdk = prev."jdk${toString javaVersion}";
          gradle = prev.gradle.override { java = jdk; };
          kotlin = prev.kotlin.override { jre = jdk; };
        })
      ];

      supportedSystems = [ "x86_64-linux" ];
      forEachSupportedSystem = f:
        nixpkgs.lib.genAttrs supportedSystems
        (system: f { pkgs = import nixpkgs { inherit system; }; });
    in {
      devShells = forEachSupportedSystem ({pkgs}: {
        default = (pkgs.buildFHSUserEnv {
          name = "java-dev-env";
          targetPkgs = pkgs:
            with pkgs; [
              jdk17
              gradle
              zlib
              snappy
            ];
          runScript = "bash";
        }).env;
      });
    };
}
