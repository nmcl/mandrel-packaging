# Using Ansible to create an environment for building and testing mandrel

## Set up a remote VM with:
```
ansible-playbook -i root@example.com, playbook.yml
```

## Set up a local container (the container must be running)
```
docker run --name=mandrel-packaging -itd fedora:32
ansible-playbook -i mandrel-packaging, -c docker playbook.yml
docker start -ai mandrel-packaging
# or
podman run --name=mandrel-packaging -itd fedora:32
ansible-playbook -i mandrel-packaging, -c podman playbook.yml
podman start -ai mandrel-packaging
```

## Use different configurations

This playbook supports different configurations (found under `./configurations`) to use a different configuration than the default (e.g. `mandrel20.1-labsjdk`) issue:
```
ansible-playbook -i root@example.com, playbook.yml -e configuration=mandrel20.1-labsjdk
```

To create a new configuration just copy an existing one and edit the values to your needs.
