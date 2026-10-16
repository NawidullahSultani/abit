package abm.calibration;

import abm.data.DataSet;
import abm.data.geo.Location;
import abm.data.geo.RegioStaR2;
import abm.data.plans.*;
import abm.data.pop.Household;
import abm.data.pop.Person;
import abm.data.pop.RemoteWorkable;
import abm.data.vehicle.Car;
import abm.io.input.CalibrationZoneToRegionTypeReader;
import abm.models.modeChoice.NestedLogitTourModeChoiceModel;
import abm.properties.AbitResources;
import de.tum.bgu.msm.data.person.Occupation;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;

import static abm.io.input.CalibrationZoneToRegionTypeReader.getRegionForZone;

public class TourModeChoiceCalibration implements ModelComponent {
    //Todo define a few calibration parameters
    static Logger logger = Logger.getLogger(TourModeChoiceCalibration.class);

    private static final int MAX_ITERATION = 100;
    private static final double TERMINATION_THRESHOLD = 0.02;

    double stepSize = 1.00;
    String inputFolder = AbitResources.instance.getString("tour.mode.coef.output");
    String tourModeObjectivesPath = AbitResources.instance.getString("tour.mode.calibration.objectives");
    String workTourModeObjectivesPath = AbitResources.instance.getString("tour.mode.calibration.objectives.work");

    DataSet dataSet;
    Map<String, Map<Purpose, Map<DayOfWeek, Map<Mode, Double>>>> objectiveTourModeShare = new HashMap<>();
    Map<String,Map<Purpose, Map<DayOfWeek, Map<Mode, Integer>>>> simulatedTourModeCount = new HashMap<>();
    Map<String,Map<Purpose, Map<DayOfWeek, Map<Mode, Double>>>> simulatedTourModeShare = new HashMap<>();
    Map<String,Map<Purpose, Map<DayOfWeek, Map<Mode, Double>>>> calibrationFactors = new HashMap<>();


    Map<Occupation, Map<RemoteWorkable, Map<RegioStaR2, Map<Mode, Double>>>> workObjectiveTourModeShare = new HashMap<>();
    Map<Occupation, Map<RemoteWorkable, Map<RegioStaR2, Map<Mode, Integer>>>> simulatedWorkTourModeCount = new HashMap<>();
    Map<Occupation, Map<RemoteWorkable, Map<RegioStaR2, Map<Mode, Double>>>> simulatedWorkTourModeShare = new HashMap<>();
    Map<Occupation, Map<RemoteWorkable, Map<RegioStaR2, Map<Mode, Double>>>> workCalibrationFactors = new HashMap<>();

    Map<Purpose, Map<Occupation, Map<String, Map<RegioStaR2, Map<Mode, Double>>>>> discretionaryObjectiveTourModeShare = new HashMap<>();
    Map<Purpose, Map<Occupation, Map<String, Map<RegioStaR2, Map<Mode, Integer>>>>> simulatedDiscretionaryTourModeCount = new HashMap<>();
    Map<Purpose, Map<Occupation, Map<String, Map<RegioStaR2, Map<Mode, Double>>>>> simulatedDiscretionaryTourModeShare = new HashMap<>();
    Map<Purpose, Map<Occupation, Map<String, Map<RegioStaR2, Map<Mode, Double>>>>> discretionaryCalibrationFactors = new HashMap<>();

    private NestedLogitTourModeChoiceModel tourModeChoiceModelCalibration;
    //private CalibrationZoneToRegionTypeReader zoneToRegionMap;

    List<String> regions = new ArrayList<>();
    {regions.add("muc");
    regions.add("nonMuc");}


    public TourModeChoiceCalibration(DataSet dataSet) {
        this.dataSet = dataSet;

    }

    @Override
    public void setup() {
        //Todo: read boolean input from the property file and create the model which needs to be calibrated
        boolean calibrateTourModeChoice = Boolean.parseBoolean(AbitResources.instance.getString("tour.mode.calibration"));
        tourModeChoiceModelCalibration = new NestedLogitTourModeChoiceModel(dataSet, calibrateTourModeChoice);

        //Todo: initialize all the data containers that might be needed for calibration
        //tourmodechoice

        for (String region : regions) {
            objectiveTourModeShare.putIfAbsent(region, new HashMap<>());
            simulatedTourModeCount.putIfAbsent(region, new HashMap<>());
            simulatedTourModeShare.putIfAbsent(region, new HashMap<>());
            calibrationFactors.putIfAbsent(region, new HashMap<>());
            for (Purpose purpose : Purpose.getSortedPurposes()) {
                objectiveTourModeShare.get(region).putIfAbsent(purpose, new HashMap<>());
                simulatedTourModeCount.get(region).putIfAbsent(purpose, new HashMap<>());
                simulatedTourModeShare.get(region).putIfAbsent(purpose, new HashMap<>());
                calibrationFactors.get(region).putIfAbsent(purpose, new HashMap<>());
                for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                    objectiveTourModeShare.get(region).get(purpose).putIfAbsent(dayOfWeek, new HashMap<>());
                    simulatedTourModeCount.get(region).get(purpose).putIfAbsent(dayOfWeek, new HashMap<>());
                    simulatedTourModeShare.get(region).get(purpose).putIfAbsent(dayOfWeek, new HashMap<>());
                    calibrationFactors.get(region).get(purpose).putIfAbsent(dayOfWeek, new HashMap<>());
                    for (Mode mode : Mode.values()) {
                        objectiveTourModeShare.get(region).get(purpose).get(dayOfWeek).putIfAbsent(mode, 0.0);
                        simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).putIfAbsent(mode, 0);
                        simulatedTourModeShare.get(region).get(purpose).get(dayOfWeek).putIfAbsent(mode, 0.0);
                        calibrationFactors.get(region).get(purpose).get(dayOfWeek).putIfAbsent(mode, 0.0);
                    }
                }
            }
        }

        for (Occupation occupation : Occupation.values()) {
            workObjectiveTourModeShare.putIfAbsent(occupation, new HashMap<>());
            simulatedWorkTourModeCount.putIfAbsent(occupation, new HashMap<>());
            simulatedWorkTourModeShare.putIfAbsent(occupation, new HashMap<>());
            workCalibrationFactors.putIfAbsent(occupation, new HashMap<>());

            for (RemoteWorkable remoteWorkable : RemoteWorkable.values()) {
                workObjectiveTourModeShare.get(occupation).putIfAbsent(remoteWorkable, new HashMap<>());
                simulatedWorkTourModeCount.get(occupation).putIfAbsent(remoteWorkable, new HashMap<>());
                simulatedWorkTourModeShare.get(occupation).putIfAbsent(remoteWorkable, new HashMap<>());
                workCalibrationFactors.get(occupation).putIfAbsent(remoteWorkable, new HashMap<>());

                for (RegioStaR2 regioStaR2 : RegioStaR2.values()) {
                    workObjectiveTourModeShare.get(occupation).get(remoteWorkable).putIfAbsent(regioStaR2, new HashMap<>());
                    simulatedWorkTourModeCount.get(occupation).get(remoteWorkable).putIfAbsent(regioStaR2, new HashMap<>());
                    simulatedWorkTourModeShare.get(occupation).get(remoteWorkable).putIfAbsent(regioStaR2, new HashMap<>());
                    workCalibrationFactors.get(occupation).get(remoteWorkable).putIfAbsent(regioStaR2, new HashMap<>());

                    for (Mode mode : Mode.getModes()) {
                        workObjectiveTourModeShare.get(occupation).get(remoteWorkable).get(regioStaR2).putIfAbsent(mode, 0.0);
                        simulatedWorkTourModeCount.get(occupation).get(remoteWorkable).get(regioStaR2).putIfAbsent(mode, 0);
                        simulatedWorkTourModeShare.get(occupation).get(remoteWorkable).get(regioStaR2).putIfAbsent(mode, 0.0);
                        workCalibrationFactors.get(occupation).get(remoteWorkable).get(regioStaR2).putIfAbsent(mode, 0.0);
                    }
                }
            }
        }
    }

    @Override
    public void load() {

        readObjectiveValues();

        summarizeSimulatedResult();
        summarizeWorkSimulatedResult();
        summarizeDiscretionarySimulatedResult();
    }

    @Override
    public void run() {

        logger.info("Start calibrating the habitual mode choice model......");
        List<Household> simulatedHouseholds = dataSet.getHouseholds().values().parallelStream().filter(Household::getSimulated).collect(Collectors.toList());

        //Todo: loop through the calibration process until criteria are met
        for (int iteration = 0; iteration < MAX_ITERATION; iteration++) {
            logger.info("Iteration......" + iteration);
            double maxDifference = 0.0;

            //........................................
            for (Occupation occupation : workObjectiveTourModeShare.keySet()) {

                for (RemoteWorkable remoteWorkable :
                        workObjectiveTourModeShare.get(occupation).keySet()) {

                    for (RegioStaR2 regioStaR2 :
                            workObjectiveTourModeShare
                                    .get(occupation)
                                    .get(remoteWorkable)
                                    .keySet()) {

                        for (Mode mode : Mode.getModes()) {

                            double observedShare =
                                    workObjectiveTourModeShare
                                            .get(occupation)
                                            .get(remoteWorkable)
                                            .get(regioStaR2)
                                            .get(mode);

                            double simulatedShare =
                                    simulatedWorkTourModeShare
                                            .get(occupation)
                                            .get(remoteWorkable)
                                            .get(regioStaR2)
                                            .get(mode);

                            double difference =
                                    observedShare - simulatedShare;

                            double factor =
                                    stepSize * difference;

                            if (mode.equals(Mode.CAR_DRIVER)) {
                                factor = 0.00;
                            }

                            workCalibrationFactors
                                    .get(occupation)
                                    .get(remoteWorkable)
                                    .get(regioStaR2)
                                    .put(mode, factor);

                            if (Math.abs(difference) > maxDifference) {
                                maxDifference = Math.abs(difference);
                            }
                        }
                    }
                }
            }

            tourModeChoiceModelCalibration
                    .updateWorkCalibrationFactor(workCalibrationFactors);
            //.................................

            for (Purpose purpose :
                    discretionaryObjectiveTourModeShare.keySet()) {

                for (Occupation occupation :
                        discretionaryObjectiveTourModeShare
                                .get(purpose)
                                .keySet()) {

                    for (String dayType :
                            discretionaryObjectiveTourModeShare
                                    .get(purpose)
                                    .get(occupation)
                                    .keySet()) {

                        for (RegioStaR2 regioStaR2 :
                                discretionaryObjectiveTourModeShare
                                        .get(purpose)
                                        .get(occupation)
                                        .get(dayType)
                                        .keySet()) {

                            for (Mode mode : Mode.getModes()) {

                                double observedShare =
                                        discretionaryObjectiveTourModeShare
                                                .get(purpose)
                                                .get(occupation)
                                                .get(dayType)
                                                .get(regioStaR2)
                                                .get(mode);

                                double simulatedShare =
                                        simulatedDiscretionaryTourModeShare
                                                .get(purpose)
                                                .get(occupation)
                                                .get(dayType)
                                                .get(regioStaR2)
                                                .get(mode);

                                double difference =
                                        observedShare - simulatedShare;

                                double factor =
                                        stepSize * (observedShare - simulatedShare);

                                if (mode.equals(Mode.CAR_DRIVER)) {
                                    factor = 0.00;
                                }

                                /*
                                 * Old special rule:
                                 *
                                 * SUNDAY + SHOPPING
                                 *
                                 * This old rule has no direct equivalent in the
                                 * new dayType segmentation, because dayType is
                                 * NO_WORKING_DAY / WFH_DAY / WFW_DAY.
                                 *
                                 * Therefore DO NOT blindly carry it over here.
                                 */

                                discretionaryCalibrationFactors
                                        .get(purpose)
                                        .get(occupation)
                                        .get(dayType)
                                        .get(regioStaR2)
                                        .put(mode, factor);

                                if (Math.abs(difference) > maxDifference) {
                                    maxDifference =
                                            Math.abs(difference);
                                }
                            }
                        }
                    }
                }
            }

            tourModeChoiceModelCalibration.updateDiscretionaryCalibrationFactor(discretionaryCalibrationFactors);

            if (maxDifference <= TERMINATION_THRESHOLD) {
                break;
            } else {
                logger.info("MAX Diff: " + maxDifference);
            }

            simulatedHouseholds.parallelStream().forEach(household -> {
                household.getVehicles().parallelStream().forEach(vehicle -> {
                    ((Car) vehicle).getBlockedTimeOfWeek().resetCarBlockedTimeOfWeekLinkedList();
                });
            });

            for (Household household : simulatedHouseholds){
                if (household.getNumberOfCars() > 0){
                    for (Purpose purpose : Purpose.getSortedPurposes()) {
                        if (purpose.equals(Purpose.WORK)) {
                            //Step 1: loop over all workers in the household, check car and transit travel time ratio
                            // car/pt ratio the smaller (more poor pt accessibility compared to car), then higher preference to use car
                            List<Person> workers = household.getPersons().stream().filter(pp -> pp.hasWorkActivity()).collect(Collectors.toList());
                            Map<Person, Double> carUsePreference = new HashMap<>();
                            for (Person person : workers) {
                                Location jobLocation;
                                double startTime;
                                if (person.getJob() != null) {
                                    jobLocation = person.getJob().getLocation();
                                    startTime = person.getJob().getStartTime_min();
                                } else {
                                    //job location for non-employed person but has a work tour, e.g. student go for interview or internship
                                    Activity workActivity = person.getPlan().getTours().values().stream().filter(tour -> tour.getMainActivity().getPurpose().equals(Purpose.WORK)).collect(Collectors.toList()).get(0).getMainActivity();
                                    jobLocation = workActivity.getLocation();
                                    startTime = workActivity.getStartTime_min();
                                }

                                int carTravelTime = dataSet.getTravelTimes().getTravelTimeInMinutes(person.getHousehold().getLocation(), jobLocation, Mode.CAR_DRIVER, startTime);
                                int transitTravelTime = dataSet.getTravelTimes().getTravelTimeInMinutes(person.getHousehold().getLocation(), jobLocation, Mode.TRAIN, startTime);
                                double carPtRatio = carTravelTime / (double) transitTravelTime;
                                carUsePreference.put(person, carPtRatio);
                            }

                            List<Map.Entry<Person, Double>> sortedPreference = new ArrayList<>(carUsePreference.entrySet());
                            Collections.sort(sortedPreference, Map.Entry.comparingByValue());

                            //Step 2: check availability and choose mode for Work tours by the order of preference
                            for (Map.Entry<Person, Double> entry : sortedPreference) {
                                entry.getKey().getPlan().getTours().values().forEach(tour -> {
                                    if (tour.getMainActivity().getPurpose().equals(Purpose.WORK)) {
                                        tourModeChoiceModelCalibration.checkCarAvailabilityAndChooseMode(household, entry.getKey(), tour, Purpose.WORK);
                                    }
                                });
                            }
                        } else {
                            //check availability and choose mode for other tours by the order of (education > accompany > other > shopping > recreational)
                            for (Person person : household.getPersons()) {
                                person.getPlan().getTours().values().forEach(tour -> {
                                    if (tour.getMainActivity().getPurpose().equals(purpose)) {
                                        tourModeChoiceModelCalibration.checkCarAvailabilityAndChooseMode(household, person, tour, purpose);
                                    }
                                });
                            }
                        }
                    }
                }else{
                    for (Person person : household.getPersons()) {
                        person.getPlan().getTours().values().forEach(tour -> {
                            tourModeChoiceModelCalibration.chooseMode(person, tour, tour.getMainActivity().getPurpose(), Boolean.FALSE);
                        });
                    }
                }
            }

            summarizeSimulatedResult();
            summarizeWorkSimulatedResult();
            summarizeDiscretionarySimulatedResult();

        }

        logger.info("Finished the calibration of habitual mode choice model.");

        try {
            printCalibrationFactors();
        } catch (FileNotFoundException e) {
            System.err.println("Output path of the calibration factor files is not correct.");
        }

    }

    private void readObjectiveValues() {
        readWorkObjectives(Path.of(workTourModeObjectivesPath));

        readDiscretionaryObjectives(Path.of(tourModeObjectivesPath));
    }

    private void readWorkObjectives(Path objectiveFile) {

        logger.info("Reading WORK tour mode choice objectives from: " + objectiveFile);

        try (BufferedReader br = Files.newBufferedReader(objectiveFile)) {

            String line;

            String header = br.readLine();

            if (header == null) {
                throw new IllegalArgumentException(
                        "WORK objective CSV file is empty: " + objectiveFile);
            }

            while ((line = br.readLine()) != null) {

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] values = line.split(",");

                if (values.length != 6) {
                    throw new IllegalArgumentException(
                            "Invalid WORK objective CSV row: " + line);
                }

                Occupation occupation =
                        Occupation.valueOf(values[1].trim().toUpperCase());

                RemoteWorkable remoteWorkable =
                        RemoteWorkable.valueOf(values[2].trim().toUpperCase());

                RegioStaR2 regioStaR2 =
                        RegioStaR2.valueOf(values[3].trim().toUpperCase());

                Mode mode =
                        Mode.valueOf(values[4].trim().toUpperCase());

                double objectiveShare =
                        Double.parseDouble(values[5].trim());

                workObjectiveTourModeShare
                        .putIfAbsent(
                                occupation,
                                new HashMap<>());

                simulatedWorkTourModeCount
                        .putIfAbsent(
                                occupation,
                                new HashMap<>());

                simulatedWorkTourModeShare
                        .putIfAbsent(
                                occupation,
                                new HashMap<>());

                workCalibrationFactors
                        .putIfAbsent(
                                occupation,
                                new HashMap<>());


                workObjectiveTourModeShare
                        .get(occupation)
                        .putIfAbsent(
                                remoteWorkable,
                                new HashMap<>());

                simulatedWorkTourModeCount
                        .get(occupation)
                        .putIfAbsent(
                                remoteWorkable,
                                new HashMap<>());

                simulatedWorkTourModeShare
                        .get(occupation)
                        .putIfAbsent(
                                remoteWorkable,
                                new HashMap<>());

                workCalibrationFactors
                        .get(occupation)
                        .putIfAbsent(
                                remoteWorkable,
                                new HashMap<>());


                workObjectiveTourModeShare
                        .get(occupation)
                        .get(remoteWorkable)
                        .putIfAbsent(
                                regioStaR2,
                                new HashMap<>());

                simulatedWorkTourModeCount
                        .get(occupation)
                        .get(remoteWorkable)
                        .putIfAbsent(
                                regioStaR2,
                                new HashMap<>());

                simulatedWorkTourModeShare
                        .get(occupation)
                        .get(remoteWorkable)
                        .putIfAbsent(
                                regioStaR2,
                                new HashMap<>());

                workCalibrationFactors
                        .get(occupation)
                        .get(remoteWorkable)
                        .putIfAbsent(
                                regioStaR2,
                                new HashMap<>());


                for (Mode availableMode : Mode.getModes()) {

                    workObjectiveTourModeShare
                            .get(occupation)
                            .get(remoteWorkable)
                            .get(regioStaR2)
                            .putIfAbsent(
                                    availableMode,
                                    0.0);

                    simulatedWorkTourModeCount
                            .get(occupation)
                            .get(remoteWorkable)
                            .get(regioStaR2)
                            .putIfAbsent(
                                    availableMode,
                                    0);

                    simulatedWorkTourModeShare
                            .get(occupation)
                            .get(remoteWorkable)
                            .get(regioStaR2)
                            .putIfAbsent(
                                    availableMode,
                                    0.0);

                    workCalibrationFactors
                            .get(occupation)
                            .get(remoteWorkable)
                            .get(regioStaR2)
                            .putIfAbsent(
                                    availableMode,
                                    0.0);
                }

                workObjectiveTourModeShare
                        .get(occupation)
                        .get(remoteWorkable)
                        .get(regioStaR2)
                        .put(mode, objectiveShare);
            }

            logger.info(
                    "Successfully loaded WORK tour mode choice objectives from: "
                            + objectiveFile);

        } catch (IOException e) {

            throw new RuntimeException(
                    "Could not read WORK tour mode choice objective file: "
                            + objectiveFile,
                    e);
        }
    }

    //WORK; EDUCATION; ACCOMPANY; OTHER; SHOP; RECR
    private void readDiscretionaryObjectives(Path objectiveFile) {

        logger.info(
                "Reading DISCRETIONARY tour mode choice objectives from: "
                        + objectiveFile);

        try (BufferedReader br = Files.newBufferedReader(objectiveFile)) {

            String line;

            String header = br.readLine();

            if (header == null) {
                throw new IllegalArgumentException(
                        "DISCRETIONARY objective CSV file is empty: "
                                + objectiveFile);
            }

            while ((line = br.readLine()) != null) {

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] values = line.split(",", -1);

                if (values.length != 6) {
                    throw new IllegalArgumentException(
                            "Invalid DISCRETIONARY objective CSV row: "
                                    + line);
                }

                Purpose purpose =
                        Purpose.valueOf(
                                values[0].trim().toUpperCase());

                Occupation occupation =
                        Occupation.valueOf(
                                values[1].trim().toUpperCase());

                String dayType =
                        values[2].trim().toUpperCase();

                RegioStaR2 regioStaR2 =
                        RegioStaR2.valueOf(
                                values[3].trim().toUpperCase());

                Mode mode =
                        Mode.valueOf(
                                values[4].trim().toUpperCase());

                double objectiveShare =
                        Double.parseDouble(
                                values[5].trim());


                // -------------------------------------------------
                // PURPOSE
                // -------------------------------------------------

                discretionaryObjectiveTourModeShare
                        .putIfAbsent(
                                purpose,
                                new HashMap<>());

                simulatedDiscretionaryTourModeCount
                        .putIfAbsent(
                                purpose,
                                new HashMap<>());

                simulatedDiscretionaryTourModeShare
                        .putIfAbsent(
                                purpose,
                                new HashMap<>());

                discretionaryCalibrationFactors
                        .putIfAbsent(
                                purpose,
                                new HashMap<>());


                // -------------------------------------------------
                // OCCUPATION
                // -------------------------------------------------

                discretionaryObjectiveTourModeShare
                        .get(purpose)
                        .putIfAbsent(
                                occupation,
                                new HashMap<>());

                simulatedDiscretionaryTourModeCount
                        .get(purpose)
                        .putIfAbsent(
                                occupation,
                                new HashMap<>());

                simulatedDiscretionaryTourModeShare
                        .get(purpose)
                        .putIfAbsent(
                                occupation,
                                new HashMap<>());

                discretionaryCalibrationFactors
                        .get(purpose)
                        .putIfAbsent(
                                occupation,
                                new HashMap<>());


                // -------------------------------------------------
                // DAY TYPE
                // -------------------------------------------------

                discretionaryObjectiveTourModeShare
                        .get(purpose)
                        .get(occupation)
                        .putIfAbsent(
                                dayType,
                                new HashMap<>());

                simulatedDiscretionaryTourModeCount
                        .get(purpose)
                        .get(occupation)
                        .putIfAbsent(
                                dayType,
                                new HashMap<>());

                simulatedDiscretionaryTourModeShare
                        .get(purpose)
                        .get(occupation)
                        .putIfAbsent(
                                dayType,
                                new HashMap<>());

                discretionaryCalibrationFactors
                        .get(purpose)
                        .get(occupation)
                        .putIfAbsent(
                                dayType,
                                new HashMap<>());


                // -------------------------------------------------
                // REGIOSTAR2
                // -------------------------------------------------

                discretionaryObjectiveTourModeShare
                        .get(purpose)
                        .get(occupation)
                        .get(dayType)
                        .putIfAbsent(
                                regioStaR2,
                                new HashMap<>());

                simulatedDiscretionaryTourModeCount
                        .get(purpose)
                        .get(occupation)
                        .get(dayType)
                        .putIfAbsent(
                                regioStaR2,
                                new HashMap<>());

                simulatedDiscretionaryTourModeShare
                        .get(purpose)
                        .get(occupation)
                        .get(dayType)
                        .putIfAbsent(
                                regioStaR2,
                                new HashMap<>());

                discretionaryCalibrationFactors
                        .get(purpose)
                        .get(occupation)
                        .get(dayType)
                        .putIfAbsent(
                                regioStaR2,
                                new HashMap<>());


                // -------------------------------------------------
                // MODE
                // -------------------------------------------------

                discretionaryObjectiveTourModeShare
                        .get(purpose)
                        .get(occupation)
                        .get(dayType)
                        .get(regioStaR2)
                        .put(
                                mode,
                                objectiveShare);

                simulatedDiscretionaryTourModeCount
                        .get(purpose)
                        .get(occupation)
                        .get(dayType)
                        .get(regioStaR2)
                        .putIfAbsent(
                                mode,
                                0);

                simulatedDiscretionaryTourModeShare
                        .get(purpose)
                        .get(occupation)
                        .get(dayType)
                        .get(regioStaR2)
                        .putIfAbsent(
                                mode,
                                0.0);

                discretionaryCalibrationFactors
                        .get(purpose)
                        .get(occupation)
                        .get(dayType)
                        .get(regioStaR2)
                        .putIfAbsent(
                                mode,
                                0.0);
            }

            logger.info(
                    "Successfully loaded DISCRETIONARY tour mode choice objectives from: "
                            + objectiveFile);

        } catch (IOException e) {

            throw new RuntimeException(
                    "Could not read DISCRETIONARY tour mode choice objective file: "
                            + objectiveFile,
                    e);
        }
    }


    private void summarizeSimulatedResult() {
        for (String region : regions) {
            for (Purpose purpose : Purpose.getSortedPurposes()) {
                for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                    for (Mode mode : Mode.values()) {
                        simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).put(mode, 0);
                        simulatedTourModeShare.get(region).get(purpose).get(dayOfWeek).put(mode, 0.0);
                    }
                }
            }
        }

        for (Household household : dataSet.getHouseholds().values()) {
            if (household.getSimulated()) {
                for (Person person : household.getPersons()) {
                    Plan plan = person.getPlan();
                    for (Tour tour : plan.getTours().values()) {
                        Purpose purpose = tour.getMainActivity().getPurpose();
                        DayOfWeek dayOfWeek = tour.getMainActivity().getDayOfWeek();
                        Mode mode = tour.getTourMode();
                        String region = getRegionForZone(tour.getActivities().get(tour.getActivities().firstKey()).
                                getLocation().getZoneId());

                        int count = simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).get(mode);
                        count = count + 1;
                        simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).replace(mode, count);

                    }

                }
            }
        }
        for (String region : regions) {
            for (Purpose purpose : Purpose.getSortedPurposes()) {
                for (Mode mode : Mode.getModes()) {

                    int cumulativeSumByMode_Weekday = 0;
                    int cumulativeSumByMode_Weekend = 0;
                    for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
                            cumulativeSumByMode_Weekend = cumulativeSumByMode_Weekend + simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).get(mode);
                        } else {
                            cumulativeSumByMode_Weekday = cumulativeSumByMode_Weekday + simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).get(mode);
                        }
                    }

                    for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
                            simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).replace(mode, cumulativeSumByMode_Weekend);
                        }else{
                            simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).replace(mode, cumulativeSumByMode_Weekday);
                        }
                    }

                }

                for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
                    int cumulativeSum = 0;
                    for (Mode mode : Mode.getModes()) {
                        cumulativeSum = cumulativeSum + simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).get(mode);
                    }
                    for (Mode mode : Mode.getModes()) {
                        double share = (double) simulatedTourModeCount.get(region).get(purpose).get(dayOfWeek).get(mode) / cumulativeSum;
                        simulatedTourModeShare.get(region).get(purpose).get(dayOfWeek).replace(mode, share);
                    }
                }
            }
        }
    }

    private void summarizeWorkSimulatedResult() {

        // Reset WORK counts and shares only for segments
        // that actually exist in the objective file.

        for (Occupation occupation : workObjectiveTourModeShare.keySet()) {

            for (RemoteWorkable remoteWorkable :
                    workObjectiveTourModeShare.get(occupation).keySet()) {

                for (RegioStaR2 regioStaR2 :
                        workObjectiveTourModeShare
                                .get(occupation)
                                .get(remoteWorkable)
                                .keySet()) {

                    for (Mode mode : Mode.getModes()) {

                        simulatedWorkTourModeCount
                                .get(occupation)
                                .get(remoteWorkable)
                                .get(regioStaR2)
                                .put(mode, 0);

                        simulatedWorkTourModeShare
                                .get(occupation)
                                .get(remoteWorkable)
                                .get(regioStaR2)
                                .put(mode, 0.0);
                    }
                }
            }
        }


        // Count simulated WORK tours
        for (Household household : dataSet.getHouseholds().values()) {

            if (!household.getSimulated()) {
                continue;
            }

            for (Person person : household.getPersons()) {

                Occupation occupation = person.getOccupation();
                RemoteWorkable remoteWorkable = getRemoteWorkable(person);

                RegioStaR2 regioStaR2 =
                        dataSet.getZones()
                                .get(person.getHousehold().getLocation().getZoneId())
                                .getRegioStaR2Type();

                // Skip combinations that do not exist in the objective file.
                if (!workObjectiveTourModeShare.containsKey(occupation)) {
                    continue;
                }

                if (!workObjectiveTourModeShare
                        .get(occupation)
                        .containsKey(remoteWorkable)) {
                    continue;
                }

                if (!workObjectiveTourModeShare
                        .get(occupation)
                        .get(remoteWorkable)
                        .containsKey(regioStaR2)) {
                    continue;
                }

                for (Tour tour : person.getPlan().getTours().values()) {

                    if (!tour.getMainActivity()
                            .getPurpose()
                            .equals(Purpose.WORK)) {
                        continue;
                    }

                    Mode mode = tour.getTourMode();

                    int count =
                            simulatedWorkTourModeCount
                                    .get(occupation)
                                    .get(remoteWorkable)
                                    .get(regioStaR2)
                                    .get(mode);

                    simulatedWorkTourModeCount
                            .get(occupation)
                            .get(remoteWorkable)
                            .get(regioStaR2)
                            .put(mode, count + 1);
                }
            }
        }


        // Calculate WORK mode shares
        for (Occupation occupation : workObjectiveTourModeShare.keySet()) {

            for (RemoteWorkable remoteWorkable :
                    workObjectiveTourModeShare.get(occupation).keySet()) {

                for (RegioStaR2 regioStaR2 :
                        workObjectiveTourModeShare
                                .get(occupation)
                                .get(remoteWorkable)
                                .keySet()) {

                    int cumulativeSum = 0;

                    for (Mode mode : Mode.getModes()) {

                        cumulativeSum +=
                                simulatedWorkTourModeCount
                                        .get(occupation)
                                        .get(remoteWorkable)
                                        .get(regioStaR2)
                                        .get(mode);
                    }

                    for (Mode mode : Mode.getModes()) {

                        double share = 0.0;

                        if (cumulativeSum > 0) {
                            share =
                                    (double)
                                            simulatedWorkTourModeCount
                                                    .get(occupation)
                                                    .get(remoteWorkable)
                                                    .get(regioStaR2)
                                                    .get(mode)
                                            / cumulativeSum;
                        }

                        simulatedWorkTourModeShare
                                .get(occupation)
                                .get(remoteWorkable)
                                .get(regioStaR2)
                                .put(mode, share);
                    }
                }
            }
        }
    }

    private void summarizeDiscretionarySimulatedResult() {

        // ---------------------------------------------------------
        // Reset only the segments that exist in the objective file
        // ---------------------------------------------------------

        for (Purpose purpose :
                discretionaryObjectiveTourModeShare.keySet()) {

            for (Occupation occupation :
                    discretionaryObjectiveTourModeShare
                            .get(purpose)
                            .keySet()) {

                for (String dayType :
                        discretionaryObjectiveTourModeShare
                                .get(purpose)
                                .get(occupation)
                                .keySet()) {

                    for (RegioStaR2 regioStaR2 :
                            discretionaryObjectiveTourModeShare
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .keySet()) {

                        for (Mode mode : Mode.getModes()) {

                            simulatedDiscretionaryTourModeCount
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .get(regioStaR2)
                                    .put(mode, 0);

                            simulatedDiscretionaryTourModeShare
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .get(regioStaR2)
                                    .put(mode, 0.0);
                        }
                    }
                }
            }
        }


        // ---------------------------------------------------------
        // Count simulated discretionary tours
        // ---------------------------------------------------------

        for (Household household :
                dataSet.getHouseholds().values()) {

            if (!household.getSimulated()) {
                continue;
            }

            for (Person person : household.getPersons()) {

                Occupation occupation =
                        person.getOccupation();

                RegioStaR2 regioStaR2 =
                        dataSet.getZones()
                                .get(
                                        person.getHousehold()
                                                .getLocation()
                                                .getZoneId())
                                .getRegioStaR2Type();


                for (Tour tour :
                        person.getPlan().getTours().values()) {

                    Purpose purpose =
                            tour.getMainActivity()
                                    .getPurpose();

                    if (!Purpose.getDiscretionaryPurposes()
                            .contains(purpose)) {
                        continue;
                    }


                    String dayType =
                            getDayType(person, tour);


                    // -------------------------------------------------
                    // Only count combinations that exist in objective
                    // -------------------------------------------------

                    if (!discretionaryObjectiveTourModeShare
                            .containsKey(purpose)) {
                        continue;
                    }

                    if (!discretionaryObjectiveTourModeShare
                            .get(purpose)
                            .containsKey(occupation)) {
                        continue;
                    }

                    if (!discretionaryObjectiveTourModeShare
                            .get(purpose)
                            .get(occupation)
                            .containsKey(dayType)) {
                        continue;
                    }

                    if (!discretionaryObjectiveTourModeShare
                            .get(purpose)
                            .get(occupation)
                            .get(dayType)
                            .containsKey(regioStaR2)) {
                        continue;
                    }


                    Mode mode = tour.getTourMode();


                    int count =
                            simulatedDiscretionaryTourModeCount
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .get(regioStaR2)
                                    .get(mode);

                    simulatedDiscretionaryTourModeCount
                            .get(purpose)
                            .get(occupation)
                            .get(dayType)
                            .get(regioStaR2)
                            .put(
                                    mode,
                                    count + 1);
                }
            }
        }


        // ---------------------------------------------------------
        // Calculate discretionary mode shares
        // ---------------------------------------------------------

        for (Purpose purpose :
                discretionaryObjectiveTourModeShare.keySet()) {

            for (Occupation occupation :
                    discretionaryObjectiveTourModeShare
                            .get(purpose)
                            .keySet()) {

                for (String dayType :
                        discretionaryObjectiveTourModeShare
                                .get(purpose)
                                .get(occupation)
                                .keySet()) {

                    for (RegioStaR2 regioStaR2 :
                            discretionaryObjectiveTourModeShare
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .keySet()) {

                        int cumulativeSum = 0;

                        for (Mode mode : Mode.getModes()) {

                            cumulativeSum +=
                                    simulatedDiscretionaryTourModeCount
                                            .get(purpose)
                                            .get(occupation)
                                            .get(dayType)
                                            .get(regioStaR2)
                                            .get(mode);
                        }

                        if (cumulativeSum > 0) {

                            for (Mode mode : Mode.getModes()) {

                                double share =
                                        (double)
                                                simulatedDiscretionaryTourModeCount
                                                        .get(purpose)
                                                        .get(occupation)
                                                        .get(dayType)
                                                        .get(regioStaR2)
                                                        .get(mode)
                                                / cumulativeSum;

                                simulatedDiscretionaryTourModeShare
                                        .get(purpose)
                                        .get(occupation)
                                        .get(dayType)
                                        .get(regioStaR2)
                                        .put(
                                                mode,
                                                share);
                            }
                        }
                    }
                }
            }
        }
    }

    private void printFinalCoefficientsTable(Map<Purpose, Map<Mode, Map<String, Double>>> finalCoefficientsTable) throws FileNotFoundException {

//        accompanyMode_nestedLogit.csv
//        shoppingMode_nestedLogit.csv
//        recreationMode_nestedLogit.csv
//        otherMode_nestedLogit.csv

        logger.info("Writing tour mode choice coefficient + calibration factors: " + inputFolder + "mandatoryMode_nestedLogit_calibrated.csv");
        PrintWriter pw = new PrintWriter(inputFolder + "mandatoryMode_nestedLogit_calibrated.csv");

        StringBuilder header = new StringBuilder("variable");
        for (Mode mode : Mode.getModes()) {
            header.append(",");
            header.append(mode);
        }
        pw.println(header);

        for (String variableNames : finalCoefficientsTable.get(Purpose.WORK).get(Mode.BUS).keySet()) {
            StringBuilder line = new StringBuilder(variableNames);

            if (variableNames.equals("calibration_education_monday_muc") || variableNames.equals("calibration_education_tuesday_muc") ||
                    variableNames.equals("calibration_education_wednesday_muc") || variableNames.equals("calibration_education_thursday_muc") ||
                    variableNames.equals("calibration_education_friday_muc") || variableNames.equals("calibration_education_saturday_muc") ||
                    variableNames.equals("calibration_education_sunday_muc")||variableNames.equals("calibration_education_monday_nonmuc") ||
                    variableNames.equals("calibration_education_tuesday_nonmuc") ||
                    variableNames.equals("calibration_education_wednesday_nonmuc") || variableNames.equals("calibration_education_thursday_nonmuc") ||
                    variableNames.equals("calibration_education_friday_nonmuc") || variableNames.equals("calibration_education_saturday_nonmuc") ||
                    variableNames.equals("calibration_education_sunday_nonmuc")) {


                for (Mode mode : Mode.getModes()) {
                    line.append(",");
                    line.append(finalCoefficientsTable.get(Purpose.EDUCATION).get(mode).get(variableNames));
                }

            } else {
                for (Mode mode : Mode.getModes()) {
                    line.append(",");
                    line.append(finalCoefficientsTable.get(Purpose.WORK).get(mode).get(variableNames));
                }
            }
            pw.println(line);
        }
        pw.close();

        for (Purpose purpose : Purpose.getDiscretionaryPurposes()) {

            logger.info("Writing tour mode choice coefficient + calibration factors: " + inputFolder + purpose + "Mode_nestedLogit_calibrated.csv");
            PrintWriter pww = new PrintWriter(inputFolder + purpose.toString().toLowerCase() + "Mode_nestedLogit_calibrated.csv");

            StringBuilder headerr = new StringBuilder("variable");
            for (Mode mode : Mode.getModes()) {
                headerr.append(",");
                headerr.append(mode);
            }
            pww.println(headerr);


            for (String variableNames : finalCoefficientsTable.get(Purpose.SHOPPING).get(Mode.BUS).keySet()) {
                StringBuilder line = new StringBuilder(variableNames);

                for (Mode mode : Mode.getModes()) {
                    line.append(",");
                    line.append(finalCoefficientsTable.get(purpose).get(mode).get(variableNames));
                }

                pww.println(line);
            }
            pww.close();

        }

    }

    private void printCalibrationFactors() throws FileNotFoundException {

        String mandatoryCalibrationFile =
                inputFolder + "mandatoryMode_nestedLogit_calibrated.csv";

        logger.info(
                "Writing WORK calibration factors: "
                        + mandatoryCalibrationFile);

        PrintWriter pw = new PrintWriter(mandatoryCalibrationFile);

        StringBuilder header = new StringBuilder("variable");

        for (Mode mode : Mode.getModes()) {
            header.append(",");
            header.append(mode);
        }

        pw.println(header);


        // WORK
        // Write only the combinations that exist in the objective file.

        for (Occupation occupation :
                workObjectiveTourModeShare.keySet()) {

            for (RemoteWorkable remoteWorkable :
                    workObjectiveTourModeShare
                            .get(occupation)
                            .keySet()) {

                for (RegioStaR2 regioStaR2 :
                        workObjectiveTourModeShare
                                .get(occupation)
                                .get(remoteWorkable)
                                .keySet()) {

                    String variableName =
                            "calibration_work_"
                                    + occupation.toString().toLowerCase()
                                    + "_"
                                    + remoteWorkable.toString().toLowerCase()
                                    + "_"
                                    + regioStaR2.toString().toLowerCase();

                    StringBuilder line =
                            new StringBuilder(variableName);

                    for (Mode mode : Mode.getModes()) {

                        double factor =
                                tourModeChoiceModelCalibration
                                        .getUpdatedWorkCalibrationFactors()
                                        .get(occupation)
                                        .get(remoteWorkable)
                                        .get(regioStaR2)
                                        .get(mode);

                        line.append(",");
                        line.append(factor);
                    }

                    pw.println(line);
                }
            }
        }

        pw.close();


        // DISCRETIONARY
        for (Purpose purpose :
                discretionaryObjectiveTourModeShare.keySet()) {

            String calibrationFile =
                    inputFolder
                            + purpose.toString().toLowerCase()
                            + "Mode_nestedLogit_calibrated.csv";

            logger.info(
                    "Writing "
                            + purpose
                            + " calibration factors: "
                            + calibrationFile);

            PrintWriter pww =
                    new PrintWriter(calibrationFile);

            StringBuilder discretionaryHeader =
                    new StringBuilder("variable");

            for (Mode mode : Mode.getModes()) {
                discretionaryHeader.append(",");
                discretionaryHeader.append(mode);
            }

            pww.println(discretionaryHeader);


            for (Occupation occupation :
                    discretionaryObjectiveTourModeShare
                            .get(purpose)
                            .keySet()) {

                for (String dayType :
                        discretionaryObjectiveTourModeShare
                                .get(purpose)
                                .get(occupation)
                                .keySet()) {

                    for (RegioStaR2 regioStaR2 :
                            discretionaryObjectiveTourModeShare
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .keySet()) {

                        String variableName =
                                "calibration_"
                                        + purpose.toString().toLowerCase()
                                        + "_"
                                        + occupation.toString().toLowerCase()
                                        + "_"
                                        + dayType.toLowerCase()
                                        + "_"
                                        + regioStaR2.toString().toLowerCase();

                        StringBuilder line =
                                new StringBuilder(variableName);

                        for (Mode mode : Mode.getModes()) {

                            double factor =
                                    tourModeChoiceModelCalibration
                                            .getUpdatedDiscretionaryCalibrationFactors()
                                            .get(purpose)
                                            .get(occupation)
                                            .get(dayType)
                                            .get(regioStaR2)
                                            .get(mode);

                            line.append(",");
                            line.append(factor);
                        }

                        pww.println(line);
                    }
                }
            }

            pww.close();
        }


        logger.info(
                "Finished writing all tour mode choice calibration factors.");
    }

    /**
     * TEMPORARY PLACEHOLDER
     *
     * This method will later contain the actual classification of:
     *
     * NO_WORKING_DAY
     * WFH_DAY
     * WFW_DAY
     *
     * For now, all tours are treated as NO_WORKING_DAY
     * so that the calibration model can run.
     */
    private String getDayType(Person person, Tour tour) {

        return "NO_WORKING_DAY";
    }
    private RemoteWorkable getRemoteWorkable(Person person) {

        return person.canRemoteWork()
                ? RemoteWorkable.TRUE
                : RemoteWorkable.FALSE;
    }

}
