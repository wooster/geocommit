#!/usr/bin/env python

import sys
import os
from subprocess import Popen, STDOUT, PIPE
from shlex import split
import tempfile
from geocommit.locationprovider import LocationProvider

def system(cmd, cwd=None):
    process = Popen(split(cmd), stderr=STDOUT, stdout=PIPE, cwd=cwd)
    # from http://stackoverflow.com/questions/1388753/how-to-get-output-from-subprocess-popen
    value = ""
    while True:
        read = process.stdout.read(1)
        value += read
        if read == '' and process.poll() != None:
            return value

class GeoGit(object):

    def __init__(self):
        '''
        >>> 1*2
        2
        '''
        self.git_bin = system("which git").strip()
        self.git_dir = system("git rev-parse --show-toplevel").strip('\n\r ')
        self.git_rev = system("git rev-parse HEAD").strip('\n\r ')

    def get_note(self):
        provider = LocationProvider.new()
        location = provider.get_location()
        if location is None:
            return None

        return location.format_long_geocommit()

    def attach_note(self):
        note = self.get_note()

        if note is None:
            print >> sys.stderr, "GeoCommit: No location available"
            return

        note_file = tempfile.NamedTemporaryFile()
        note_file.write(note)
        note_file.flush()

        command = self.git_bin + " "

        command += "notes --ref=geocommit add -F " + note_file.name + " "
        command += self.git_rev

        print command
        sys.stdout.flush()

        system(command, self.git_dir)

        note_file.close()

def main(argv=None):
    if argv is None:
        argv = sys.argv

    geocommit = GeoGit()
    geocommit.attach_note()

if __name__ == "__main__":
    sys.exit(main())
