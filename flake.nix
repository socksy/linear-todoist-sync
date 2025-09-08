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
              mkdir -p $out/bin $out/share/linear-todoist-sync
              cp -r src bb.edn $out/share/linear-todoist-sync/
              cp *.example $out/share/linear-todoist-sync/
              
              cat > $out/bin/linear-todoist-sync << EOF
              #!/usr/bin/env bash
              ORIGINAL_PWD="\$(pwd)"
              cd "$out/share/linear-todoist-sync"
              exec ${pkgs.babashka}/bin/bb sync --work-dir "\$ORIGINAL_PWD" "\$@"
              EOF
              chmod +x $out/bin/linear-todoist-sync
            '';
          };
        }
      );
    };
}