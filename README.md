# changeSHARK
[![Build Status](https://travis-ci.org/smartshark/changeSHARK.svg?branch=master)](https://travis-ci.org/ftrautsch/BugFixClassifier)

### Description 
The changeSHARK provides two different things: 

1) A library which is able to classify bugfixes using 
[ChangeDistiller](https://github.com/ftrautsch/tools-changedistiller) and the classification schema based
on this [paper](https://www.sciencedirect.com/science/article/pii/S0950584917301313).

2) A plugin for the [SmartSHARK](http://github.com/smartshark/) mining infrastructure, which stores change 
classification data into the MongoDB used by SmartSHARK. 