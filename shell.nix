{ pkgs ? import <nixpkgs> { } }:

let
  jdk = pkgs.jdk17;
in
pkgs.mkShell {
  packages = [
    jdk
    pkgs.pnpm
    (pkgs.maven.override { jdk_headless = jdk; })
  ];

  JAVA_HOME = "${jdk}";
}
