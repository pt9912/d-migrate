class DMigrate < Formula
  desc "Database-agnostic CLI tool for schema migration and data management"
  homepage "https://github.com/pt9912/d-migrate"
  # Finalize url and sha256 from the published release ZIP during the release cut.
  url "https://github.com/pt9912/d-migrate/releases/download/v0.5.0/d-migrate-0.5.0.zip"
  sha256 "REPLACE_WITH_RELEASE_ZIP_SHA256"
  license "MIT"

  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    (bin/"d-migrate").write_env_script(
      libexec/"bin/d-migrate",
      Language::Java.overridable_java_home_env("21"),
    )
  end

  test do
    assert_match "Database-agnostic migration", shell_output("#{bin}/d-migrate --help")
  end
end
