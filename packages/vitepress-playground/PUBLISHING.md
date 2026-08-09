# Publishing the playground package

`@datapack-sandbox/vitepress-playground` is the public npm distribution. The Kotlin/JS and TeaVM
browser engines are implementation details bundled into this package; the root workspace and VS
Code extension are not npm publications.

## One-time bootstrap

1. Create the `datapack-sandbox` organization on npm and ensure the publishing account can publish
   public packages in that scope.
2. Bootstrap the first version by choosing one of these routes:

   - Publish from an authenticated workstation:

     ```bash
     npm login
     npm run playground:check
     npm publish --workspace @datapack-sandbox/vitepress-playground
     ```

   - Add a granular npm access token with publish permission and 2FA bypass as the `NPM_TOKEN`
     GitHub repository secret, then push the matching version tag as described below. The workflow
     uses this token only as a fallback until trusted publishing is configured.

3. On the npm package settings page, add a GitHub Actions trusted publisher with these exact values:

   - Organization or user: `Alumopper`
   - Repository: `DatapackSandbox`
   - Workflow filename: `publish-npm.yml`
   - Allowed action: `npm publish`

4. After a successful OIDC release, remove the `NPM_TOKEN` secret. The workflow grants only
   `contents: read` and `id-token: write`; npm generates provenance automatically for a public
   package published from this public repository.

## Releasing a version

1. Update the workspace version and lockfile, for example:

   ```bash
   npm version patch --workspace @datapack-sandbox/vitepress-playground --no-git-tag-version
   ```

2. Run `npm run playground:check` and commit the version plus package changes.
3. Create and push a tag matching the package version exactly:

   ```bash
   git tag vitepress-playground-v0.2.1
   git push origin vitepress-playground-v0.2.1
   ```

The tag starts `.github/workflows/publish-npm.yml`. The workflow rejects mismatched tag/package
versions, rebuilds and tests the browser package, checks the tarball, and publishes it to npm with
public access. npm package versions are immutable, so never reuse an existing version tag.
