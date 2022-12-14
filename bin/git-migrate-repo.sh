# From https://stackoverflow.com/a/43345686/1025412
# Usage:
#   git-migrate-repo.sh https://github.com/district0x/district-ui-component-active-account.git browser/district-ui-component-active-account
repo="$1"
dir="$(echo "$2" | sed 's/\/$//')"
path="$(pwd)"

tmp="$(mktemp -d)"
remote="$(echo "$tmp" | sed 's/\///g'| sed 's/\./_/g')"

# git clone "$repo" "$tmp"
cp -r "$repo" "$tmp"
cd "$tmp"

git filter-branch --index-filter '
    git ls-files -s |
    sed "s,\t,&'"$dir"'/," |
    GIT_INDEX_FILE="$GIT_INDEX_FILE.new" git update-index --index-info &&
    mv "$GIT_INDEX_FILE.new" "$GIT_INDEX_FILE"
' HEAD

cd "$path"
git remote add -f "$remote" "file://$tmp/.git"
git pull "$remote/master"
git merge --allow-unrelated-histories -m "Merge repo $repo into master" --no-edit "$remote/master"
git remote remove "$remote"
rm -rf "$tmp"
