# BugFixClassifier
[![Build Status](https://travis-ci.org/ftrautsch/BugFixClassifier.svg?branch=master)](https://travis-ci.org/ftrautsch/BugFixClassifier)

### Description 
The BugFixClassifier provides two different things: 

1) A library which is able to classify bugfixes using 
[ChangeDistiller](https://bitbucket.org/bill_kidwell/tools-changedistiller/) and the classification schema based
on this [paper](https://www.sciencedirect.com/science/article/pii/S0950584917301313).

2) A plugin for the [SmartSHARK](http://github.com/smartshark/) mining infrastructure, which stores change 
classification data into the MongoDB used by SmartSHARK. 