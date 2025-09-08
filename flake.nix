{
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  
  outputs = { nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" "x86_64-darwin" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      packages = forAllSystems (system:
        let pkgs = nixpkgs.legacyPackages.${system}; in {
          default = pkgs.stdenv.mkDerivation {
            name = "linear-todoist-sync";
            src = ./.;
            buildInputs = [ pkgs.babashka ];
            installPhase = ''
              mkdir -p $out/bin
              cp -r . $out/
              echo '#!/usr/bin/env bash' > $out/bin/linear-todoist-sync
              echo "cd $out && ${pkgs.babashka}/bin/bb sync \"\$@\"" >> $out/bin/linear-todoist-sync
              chmod +x $out/bin/linear-todoist-sync
            '';
          };
        }
      );
    };
}