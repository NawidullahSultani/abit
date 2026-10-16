package abm.models.modeChoice;

import abm.data.DataSet;
import abm.data.geo.RegioStaR2;
import abm.data.geo.RegioStaR7;
import abm.data.plans.*;
import abm.data.pop.Household;
import abm.data.pop.Person;
import abm.data.pop.RemoteWorkable;
import abm.data.vehicle.Car;
import abm.data.vehicle.Vehicle;
import abm.io.input.CalibrationZoneToRegionTypeReader;
import abm.io.input.CoefficientsReader;
import abm.properties.AbitResources;
import abm.utils.AbitUtils;
import abm.utils.LogitTools;
import de.tum.bgu.msm.data.person.Disability;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Occupation;
import de.tum.bgu.msm.util.MitoUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.utils.collections.Tuple;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;

import static abm.io.input.CalibrationZoneToRegionTypeReader.getRegionForZone;


public class NestedLogitTourModeChoiceModel implements TourModeChoice {

    private final static Logger logger = LogManager.getLogger(NestedLogitTourModeChoiceModel.class);
    private final DataSet dataSet;
    private boolean runCalibration = false;
    private Map<Purpose, Map<Mode, Map<String, Double>>> purposeModeCoefficients;

    private static final LogitTools<abm.data.plans.Mode> logitTools = new LogitTools<>(abm.data.plans.Mode.class);
    private Map<Purpose, List<Tuple<EnumSet<abm.data.plans.Mode>, Double>>> nests = null;

    Map<Occupation, Map<RemoteWorkable, Map<RegioStaR2, Map<Mode, Double>>>> updatedWorkCalibrationFactors = new HashMap<>();
    Map<Purpose, Map<Occupation, Map<String, Map<RegioStaR2, Map<Mode, Double>>>>> updatedDiscretionaryCalibrationFactors;

    Path tourModeCalibrationOutputPath = Path.of(AbitResources.instance.getString("tour.mode.calibration.output"));


    private final static double fuelCostEurosPerKm = 0.065;
    private final static double transitFareEurosPerKm = 0.12;

    private static final double SPEED_WALK_KMH = 4.;
    private static final double SPEED_BICYCLE_KMH = 10.;

    //.........
    public NestedLogitTourModeChoiceModel(DataSet dataSet) {
        this(dataSet, false);
    }

    public NestedLogitTourModeChoiceModel(DataSet dataSet, boolean runCalibration) {

        this.dataSet = dataSet;
        this.runCalibration = runCalibration;

        this.purposeModeCoefficients = new HashMap<>();

        Map<Purpose, List<Tuple<EnumSet<abm.data.plans.Mode>, Double>>> nests =
                new HashMap<>();

        for (Purpose purpose : Purpose.getAllPurposes()) {

            Map<Mode, Map<String, Double>> modeCoefficients =
                    new HashMap<>();

            List<Tuple<EnumSet<abm.data.plans.Mode>, Double>> nestsByPurpose =
                    new ArrayList<>();

            Path pathToFilePurpose;

            if (purpose == Purpose.EDUCATION || purpose == Purpose.WORK) {

                pathToFilePurpose =
                        Path.of(
                                AbitResources.instance.getString("tour.mode.coef")
                                        + "mandatoryMode_nestedLogit.csv"
                        );

            } else {

                pathToFilePurpose =
                        Path.of(
                                AbitResources.instance.getString("tour.mode.coef")
                                        + purpose.toString().toLowerCase()
                                        + "Mode_nestedLogit.csv"
                        );
            }

            for (Mode mode : Mode.getModes()) {

                String columnName = mode.toString();

                Map<String, Double> coefficients =
                        new CoefficientsReader(
                                dataSet,
                                columnName,
                                pathToFilePurpose
                        ).readCoefficients();

                modeCoefficients.put(mode, coefficients);

                if (mode == Mode.CAR_DRIVER) {

                    nestsByPurpose.add(
                            new Tuple<>(
                                    EnumSet.of(
                                            Mode.CAR_DRIVER,
                                            Mode.CAR_PASSENGER
                                    ),
                                    coefficients.get("nestingCoefficient")
                            )
                    );

                } else if (mode == Mode.TRAIN) {

                    nestsByPurpose.add(
                            new Tuple<>(
                                    EnumSet.of(
                                            Mode.TRAIN,
                                            Mode.TRAM_METRO,
                                            Mode.BUS
                                    ),
                                    coefficients.get("nestingCoefficient")
                            )
                    );

                } else if (mode == Mode.BIKE) {

                    nestsByPurpose.add(
                            new Tuple<>(
                                    EnumSet.of(
                                            Mode.WALK,
                                            Mode.BIKE
                                    ),
                                    coefficients.get("nestingCoefficient")
                            )
                    );
                }
            }

            purposeModeCoefficients.put(
                    purpose,
                    modeCoefficients
            );

            nests.put(
                    purpose,
                    nestsByPurpose
            );
        }

        this.purposeModeCoefficients = purposeModeCoefficients;
        this.nests = nests;

        initializeCalibrationFactors();

        if (!runCalibration) {
            loadCalibrationFactors();
        }
    }
    //..........

    private void initializeCalibrationFactors() {

        updatedWorkCalibrationFactors = new HashMap<>();

        for (Occupation occupation : Occupation.values()) {

            updatedWorkCalibrationFactors.put(
                    occupation,
                    new HashMap<>()
            );

            for (RemoteWorkable remoteWorkable : RemoteWorkable.values()) {

                updatedWorkCalibrationFactors
                        .get(occupation)
                        .put(
                                remoteWorkable,
                                new HashMap<>()
                        );

                for (RegioStaR2 regioStaR2 : RegioStaR2.values()) {

                    updatedWorkCalibrationFactors
                            .get(occupation)
                            .get(remoteWorkable)
                            .put(
                                    regioStaR2,
                                    new HashMap<>()
                            );

                    for (Mode mode : Mode.getModes()) {

                        updatedWorkCalibrationFactors
                                .get(occupation)
                                .get(remoteWorkable)
                                .get(regioStaR2)
                                .put(
                                        mode,
                                        0.0
                                );
                    }
                }
            }
        }

        updatedDiscretionaryCalibrationFactors =
                new HashMap<>();

        for (Purpose purpose :
                Purpose.getDiscretionaryPurposes()) {

            updatedDiscretionaryCalibrationFactors
                    .put(
                            purpose,
                            new HashMap<>());

            for (Occupation occupation :
                    Occupation.values()) {

                updatedDiscretionaryCalibrationFactors
                        .get(purpose)
                        .put(
                                occupation,
                                new HashMap<>());

                for (String dayType :
                        Arrays.asList(
                                "NO_WORKING_DAY",
                                "WFH_DAY",
                                "WFW_DAY")) {

                    updatedDiscretionaryCalibrationFactors
                            .get(purpose)
                            .get(occupation)
                            .put(
                                    dayType,
                                    new HashMap<>());

                    for (RegioStaR2 regioStaR2 :
                            RegioStaR2.values()) {

                        updatedDiscretionaryCalibrationFactors
                                .get(purpose)
                                .get(occupation)
                                .get(dayType)
                                .put(
                                        regioStaR2,
                                        new HashMap<>());

                        for (Mode mode : Mode.getModes()) {

                            updatedDiscretionaryCalibrationFactors
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .get(regioStaR2)
                                    .put(
                                            mode,
                                            0.0);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void chooseMode(Person person, Tour tour) {
        //This is only used for the simple mode choice that does not have coefficients by mode in separate files by purpose
    }

    @Override
    public void chooseMode(Person person, Tour tour, Purpose purpose) {

    }

    @Override
    public void checkCarAvailabilityAndChooseMode(Household household, Person person, Tour tour, Purpose purpose) {
        boolean carAvailable = Boolean.FALSE;
        Vehicle selectedVehicle = null;
        //TODO: how to define the car use start and end time
        int carUseStartTime_min = tour.getLegs().get(tour.getLegs().firstKey()).getNextActivity().getStartTime_min() - tour.getLegs().get(tour.getLegs().firstKey()).getTravelTime_min();
        int carUseEndTime_min = tour.getLegs().get(tour.getLegs().lastKey()).getPreviousActivity().getEndTime_min() + tour.getLegs().get(tour.getLegs().lastKey()).getTravelTime_min();

        for (Vehicle vehicle : household.getVehicles()) {
            if (vehicle instanceof Car) {
                carAvailable = ((Car) vehicle).getBlockedTimeOfWeek().isAvailable(carUseStartTime_min, carUseEndTime_min);
                if (carAvailable) {
                    selectedVehicle = vehicle;
                    break;
                }
            }
        }

        Mode selectedMode = chooseMode(person, tour, purpose, carAvailable);
        if (selectedVehicle != null & selectedMode.equals(Mode.CAR_DRIVER)) {
            ((Car) selectedVehicle).getBlockedTimeOfWeek().blockTime(carUseStartTime_min, carUseEndTime_min);
            tour.setCar(selectedVehicle);
        }
    }

    @Override
    public Mode chooseMode(Person person, Tour tour, Purpose purpose, Boolean carAvailable) {
        Household household = person.getHousehold();
        EnumMap<Mode, Double> utilities = new EnumMap<Mode, Double>(Mode.class);
        for (Mode mode : Mode.getModes()) {
            if (mode == Mode.CAR_DRIVER && !carAvailable) {
                utilities.put(mode, Double.NEGATIVE_INFINITY);
            } else {
                utilities.put(mode, calculateUtilityForThisMode(person, tour, purpose, mode, household));
            }
        }


        if (utilities == null) return null;
        EnumMap<Mode, Double> probabilities = logitTools.getProbabilities(utilities, nests.get(purpose));


        final Mode selected = MitoUtil.select(probabilities, AbitUtils.getRandomObject());

        tour.getLegs().values().forEach(leg -> leg.setLegMode(selected));
        tour.setTourMode(selected);
        return selected;

    }

//    private double calculateUtilityForThisMode(Person person, Tour tour, Purpose purpose, Mode mode,
//                                               Household household) {
//        // Intercept
//        double utility = purposeModeCoefficients.get(purpose).get(mode).get("INTERCEPT");
//
//        // Sex
//        if (person.getGender().equals(Gender.FEMALE)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.female", 0.);
//        }
//
//        // Age
//        if (person.getAge() < 19) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_1", 0.);
//        } else if (person.getAge() < 30) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_2", 0.);
//        } else if (person.getAge() < 50) {
//            utility += 0;
//        } else if (person.getAge() < 70) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_45", 0.);
//        } else {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_6", 0.);
//        }
//
//        // Mobility restriction
//        if (!person.getDisability().equals(Disability.WITHOUT)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mobilityRestricted", 0.);
//        }
//
//        // Household size
//        utility += person.getHousehold().getPersons().size() * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.size", 0.);
//
//        // Household Children
//        int numChildren = (int) person.getHousehold().getPersons().stream().filter(p -> p.getAge() <= 18).count();
//
//        if (numChildren > 0) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.children", 0.);
//        }
//
//        // Household BBSR Region Type
//        RegioStaR7 regionType = dataSet.getZones().get(person.getHousehold().getLocation().getZoneId()).getRegioStaR7Type();
//        if (regionType.equals(RegioStaR7.URBAN_METROPOLIS) || regionType.equals(RegioStaR7.URBAN_REGIOPOLIS)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_12", 0.);
//        } else if (regionType.equals(RegioStaR7.URBAN_MEDIUM_SIZED_CITY) || regionType.equals(RegioStaR7.URBAN_PROVINCIAL)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_34", 0.);
//        } else if (regionType.equals(RegioStaR7.RURAL_CENTRAL_CITY) || regionType.equals(RegioStaR7.RURAL_URBAN_AREA)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_56", 0.);
//        } else {
//            utility += 0;
//        }
//
//        // Urban or rural
//        RegioStaR2 regionType2 = dataSet.getZones().get(person.getHousehold().getLocation().getZoneId()).getRegioStaR2Type();
//        if (regionType2.equals(RegioStaR2.URBAN)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.urban", 0.);
//        } else {
//            utility += 0;
//        }
//
//        //generalized costs
//        EnumMap<Mode, Double> generalizedCosts = calculateGeneralizedCosts(purpose, household, tour);
//
//        double gc = generalizedCosts.get(mode);
//        utility += gc * purposeModeCoefficients.get(purpose).get(mode).get("gc");
//
//        //number of days with work or education tours
//        int numDaysWork = 0;
//        int numDaysEducation = 0;
//        int numDaysMandatory = 0;
//
//        final List<Tour> tourList = person.getPlan().getTours().values().stream().filter(tourMandatory -> Purpose.getMandatoryPurposes().contains(tour.getMainActivity().getPurpose())).collect(Collectors.toList());
//
//        int[] daysOfWork = new int[]{0, 0, 0, 0, 0, 0, 0};
//        int[] daysOfEducation = new int[]{0, 0, 0, 0, 0, 0, 0};
//
//        for (Tour tourMandatory : tourList) {
//
//            if (tourMandatory.getMainActivity().getPurpose().equals(Purpose.WORK)) {
//                int dayOfWeek = tourMandatory.getMainActivity().getDayOfWeek().getValue();
//                if (daysOfWork[dayOfWeek - 1] == 0) {
//                    daysOfWork[dayOfWeek - 1] = 1;
//                }
//            } else {
//                int dayOfWeek = tourMandatory.getMainActivity().getDayOfWeek().getValue();
//                if (daysOfEducation[dayOfWeek - 1] == 0) {
//                    daysOfEducation[dayOfWeek - 1] = 1;
//                }
//            }
//        }
//
//        numDaysWork = Arrays.stream(daysOfWork).sum();
//        numDaysEducation = Arrays.stream(daysOfEducation).sum();
//        numDaysMandatory = numDaysWork + numDaysEducation;
//
//        utility += numDaysWork * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_edu", 0.);
//        utility += numDaysEducation * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_work", 0.);
//        utility += numDaysMandatory * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_mand", 0.);
//
//        //tour purpose
//        if (purpose.equals(Purpose.EDUCATION)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("tour.purpose_education", 0.);
//        } else {
//            utility += 0;
//        }
//
//        //number of stops on tour
//        if (tour.getActivities().size() >= 2) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("tour.stops2orMore", 0.);
//        } else {
//            utility += 0;
//        }
//
//
//        //habitual mode
//        switch (person.getHabitualMode()) {
//            case CAR_DRIVER:
//                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_carD", 0.);
//                break;
//            case CAR_PASSENGER:
//                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_carP", 0.);
//                break;
//            case BIKE:
//                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_cycle", 0.);
//                break;
//            case PT:
//                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_PT", 0.);
//                break;
//            case WALK:
//                utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_walk", 0.);
//                break;
//            case UNKNOWN:
//                break;
//        }
//
//        //to account for calibration factors
//        String region = getRegionForZone(tour.getActivities().get(tour.getActivities().firstKey()).
//                getLocation().getZoneId());
//
//        if (purpose.equals(Purpose.WORK) || purpose.equals(Purpose.EDUCATION)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).get("calibration_" + purpose.toString().toLowerCase() + "_" +
//                    tour.getMainActivity().getDayOfWeek().toString().toLowerCase() + "_" + region.toLowerCase());
//
//        } else {
//
//            utility += purposeModeCoefficients.get(purpose).get(mode).get("calibration_" + tour.getMainActivity().getDayOfWeek().toString().toLowerCase() +
//                     "_" + region.toLowerCase());
//        }
//
//
//
//        if (purpose.equals(Purpose.WORK)) {
//            Occupation occupation = person.getOccupation();
//            RemoteWorkable remoteWorkable = getRemoteWorkable(person);
//            RegioStaR2 regioStaR2 = dataSet.getZones().get(person.getHousehold().getLocation().getZoneId()).getRegioStaR2Type();
//            utility += updatedWorkCalibrationFactors.get(occupation).get(remoteWorkable).get(regioStaR2).get(mode);
//        } else if (Purpose.getDiscretionaryPurposes().contains(purpose)) {
//            utility += updatedCalibrationFactors.get(region).get(purpose).get(tour.getMainActivity().getDayOfWeek()).get(mode);
//        }
//        return utility;
//    }

    private double calculateUtilityForThisMode(Person person, Tour tour, Purpose purpose, Mode mode, Household household) {
        // Intercept
        double utility = purposeModeCoefficients.get(purpose).get(mode).get("INTERCEPT");

        // Sex
        if (person.getGender().equals(Gender.FEMALE)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.female", 0.);
        }

        // Age
        if (person.getAge() < 19) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_1", 0.);
        } else if (person.getAge() < 30) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_2", 0.);
        } else if (person.getAge() < 50) {
            utility += 0;
        } else if (person.getAge() < 70) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_45", 0.);
        } else {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_6", 0.);
        }

        // Mobility restriction
        if (!person.getDisability().equals(Disability.WITHOUT)) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mobilityRestricted", 0.);
        }

        // Household size
        utility += person.getHousehold().getPersons().size()
                * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.size", 0.);

        // Household Children
        int numChildren = (int) person.getHousehold().getPersons().stream()
                .filter(p -> p.getAge() <= 18)
                .count();

        if (numChildren > 0) {
            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.children", 0.);
        }

        // Household BBSR Region Type
        RegioStaR7 regionType =
                dataSet.getZones()
                        .get(person.getHousehold().getLocation().getZoneId())
                        .getRegioStaR7Type();

        if (regionType.equals(RegioStaR7.URBAN_METROPOLIS)
                || regionType.equals(RegioStaR7.URBAN_REGIOPOLIS)) {

            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault("hh.regionType_12", 0.);

        } else if (regionType.equals(RegioStaR7.URBAN_MEDIUM_SIZED_CITY)
                || regionType.equals(RegioStaR7.URBAN_PROVINCIAL)) {

            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault("hh.regionType_34", 0.);

        } else if (regionType.equals(RegioStaR7.RURAL_CENTRAL_CITY)
                || regionType.equals(RegioStaR7.RURAL_URBAN_AREA)) {

            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault("hh.regionType_56", 0.);

        } else {
            utility += 0;
        }

        // Urban or rural
        RegioStaR2 regionType2 =
                dataSet.getZones()
                        .get(person.getHousehold().getLocation().getZoneId())
                        .getRegioStaR2Type();

        if (regionType2.equals(RegioStaR2.URBAN)) {
            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault("hh.urban", 0.);
        } else {
            utility += 0;
        }

        // generalized costs
        EnumMap<Mode, Double> generalizedCosts =
                calculateGeneralizedCosts(
                        purpose,
                        household,
                        tour);

        double gc = generalizedCosts.get(mode);

        utility += gc
                * purposeModeCoefficients
                .get(purpose)
                .get(mode)
                .get("gc");

        // number of days with work or education tours
        int numDaysWork = 0;
        int numDaysEducation = 0;
        int numDaysMandatory = 0;

        final List<Tour> tourList =
                person.getPlan()
                        .getTours()
                        .values()
                        .stream()
                        .filter(tourMandatory ->
                                Purpose.getMandatoryPurposes()
                                        .contains(tour.getMainActivity().getPurpose()))
                        .collect(Collectors.toList());

        int[] daysOfWork =
                new int[]{0, 0, 0, 0, 0, 0, 0};

        int[] daysOfEducation =
                new int[]{0, 0, 0, 0, 0, 0, 0};

        for (Tour tourMandatory : tourList) {

            if (tourMandatory.getMainActivity()
                    .getPurpose()
                    .equals(Purpose.WORK)) {

                int dayOfWeek =
                        tourMandatory
                                .getMainActivity()
                                .getDayOfWeek()
                                .getValue();

                if (daysOfWork[dayOfWeek - 1] == 0) {
                    daysOfWork[dayOfWeek - 1] = 1;
                }

            } else {

                int dayOfWeek =
                        tourMandatory
                                .getMainActivity()
                                .getDayOfWeek()
                                .getValue();

                if (daysOfEducation[dayOfWeek - 1] == 0) {
                    daysOfEducation[dayOfWeek - 1] = 1;
                }
            }
        }

        numDaysWork =
                Arrays.stream(daysOfWork).sum();

        numDaysEducation =
                Arrays.stream(daysOfEducation).sum();

        numDaysMandatory =
                numDaysWork + numDaysEducation;

        utility +=
                numDaysWork
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "num_days_edu",
                                0.);

        utility +=
                numDaysEducation
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "num_days_work",
                                0.);

        utility +=
                numDaysMandatory
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "num_days_mand",
                                0.);

        // tour purpose
        if (purpose.equals(Purpose.EDUCATION)) {
            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault(
                            "tour.purpose_education",
                            0.);
        } else {
            utility += 0;
        }

        // number of stops on tour
        if (tour.getActivities().size() >= 2) {
            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault(
                            "tour.stops2orMore",
                            0.);
        } else {
            utility += 0;
        }

        // habitual mode
        switch (person.getHabitualMode()) {

            case CAR_DRIVER:
                utility += purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_carD",
                                0.);
                break;

            case CAR_PASSENGER:
                utility += purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_carP",
                                0.);
                break;

            case BIKE:
                utility += purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_cycle",
                                0.);
                break;

            case PT:
                utility += purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_PT",
                                0.);
                break;

            case WALK:
                utility += purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_walk",
                                0.);
                break;

            case UNKNOWN:
                break;
        }


        // to account for calibration factors
        String region =
                getRegionForZone(
                        tour.getActivities()
                                .get(
                                        tour.getActivities()
                                                .firstKey())
                                .getLocation()
                                .getZoneId());


        if (purpose.equals(Purpose.WORK)) {

            // ---------------------------------------------------------
            // WORK CALIBRATION
            // ---------------------------------------------------------

            Occupation occupation =
                    person.getOccupation();

            RemoteWorkable remoteWorkable =
                    getRemoteWorkable(person);

            RegioStaR2 regioStaR2 =
                    dataSet.getZones()
                            .get(
                                    person.getHousehold()
                                            .getLocation()
                                            .getZoneId())
                            .getRegioStaR2Type();

            utility +=
                    updatedWorkCalibrationFactors
                            .get(occupation)
                            .get(remoteWorkable)
                            .get(regioStaR2)
                            .get(mode);


        } else if (Purpose.getDiscretionaryPurposes()
                .contains(purpose)) {

            // ---------------------------------------------------------
            // DISCRETIONARY CALIBRATION
            // ---------------------------------------------------------

            Occupation occupation =
                    person.getOccupation();

            RegioStaR2 regioStaR2 =
                    dataSet.getZones()
                            .get(
                                    person.getHousehold()
                                            .getLocation()
                                            .getZoneId())
                            .getRegioStaR2Type();

            String dayType =
                    getDayType(person, tour);

            utility +=
                    updatedDiscretionaryCalibrationFactors
                            .get(purpose)
                            .get(occupation)
                            .get(dayType)
                            .get(regioStaR2)
                            .get(mode);
        }

        return utility;
    }

    public double calculateModeChoiceLogsumForThisODPairForBase(Person person, Tour tour, Purpose purpose, Map<String, Double> attributes){
        Map<Mode, Double> utilityMap = new HashMap<>();

        for (Mode mode : Mode.getModes()){
            utilityMap.put(mode, this.calculateModeChoiceLogsumForThisMode(person, tour, purpose, mode, attributes));
        }

        final Double nestingCoefficientAutoModes = purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("nestingCoefficient");
        final Double nestingCoefficientPtModes = purposeModeCoefficients.get(purpose).get(Mode.BUS).get("nestingCoefficient");
        final Double nestingCoefficientActiveModes = purposeModeCoefficients.get(purpose).get(Mode.WALK).get("nestingCoefficient");

        double expSumAuto = Math.exp(nestingCoefficientAutoModes * Math.log(Math.exp(utilityMap.get(Mode.CAR_DRIVER)/nestingCoefficientAutoModes) + Math.exp(utilityMap.get(Mode.CAR_PASSENGER)/nestingCoefficientAutoModes)));
        double expSumPt = Math.exp(nestingCoefficientPtModes * Math.log(Math.exp(utilityMap.get(Mode.BUS)/nestingCoefficientPtModes) + Math.exp(utilityMap.get(Mode.TRAM_METRO)/nestingCoefficientPtModes) + Math.exp(utilityMap.get(Mode.TRAIN)/nestingCoefficientPtModes)));
        double expSumActive = Math.exp(nestingCoefficientActiveModes * Math.log(Math.exp(utilityMap.get(Mode.BIKE)/nestingCoefficientActiveModes) + Math.exp(utilityMap.get(Mode.WALK)/nestingCoefficientActiveModes)));
        return Math.log(expSumAuto + expSumPt + expSumActive);
    }

    public double calculateModeChoiceLogsumForThisODPairForLowEmissionZoneRestriction(Person person, Tour tour, Purpose purpose, Map<String, Double> attributes, boolean isLowEmissionZoneRestriction){

        Map<Mode, Double> utilityMap = new HashMap<>();

        for (Mode mode : Mode.getModes()){
            utilityMap.put(mode, this.calculateModeChoiceLogsumForThisMode(person, tour, purpose, mode, attributes));
        }

        if (isLowEmissionZoneRestriction){
            utilityMap.put(Mode.CAR_DRIVER, Double.NEGATIVE_INFINITY);
            utilityMap.put(Mode.CAR_PASSENGER, Double.NEGATIVE_INFINITY);
        }

        final Double nestingCoefficientAutoModes = purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("nestingCoefficient");
        final Double nestingCoefficientPtModes = purposeModeCoefficients.get(purpose).get(Mode.BUS).get("nestingCoefficient");
        final Double nestingCoefficientActiveModes = purposeModeCoefficients.get(purpose).get(Mode.WALK).get("nestingCoefficient");

        double expSumAuto = Math.exp(nestingCoefficientAutoModes * Math.log(Math.exp(utilityMap.get(Mode.CAR_DRIVER)/nestingCoefficientAutoModes) + Math.exp(utilityMap.get(Mode.CAR_PASSENGER)/nestingCoefficientAutoModes)));
        double expSumPt = Math.exp(nestingCoefficientPtModes * Math.log(Math.exp(utilityMap.get(Mode.BUS)/nestingCoefficientPtModes) + Math.exp(utilityMap.get(Mode.TRAM_METRO)/nestingCoefficientPtModes) + Math.exp(utilityMap.get(Mode.TRAIN)/nestingCoefficientPtModes)));
        double expSumActive = Math.exp(nestingCoefficientActiveModes * Math.log(Math.exp(utilityMap.get(Mode.BIKE)/nestingCoefficientActiveModes) + Math.exp(utilityMap.get(Mode.WALK)/nestingCoefficientActiveModes)));
        return Math.log(expSumAuto + expSumPt + expSumActive);
    }

//    private double calculateModeChoiceLogsumForThisMode(Person person, Tour tour, Purpose purpose, Mode mode, Map<String, Double> attributes) {
//        // Intercept
//        double utility = purposeModeCoefficients.get(purpose).get(mode).get("INTERCEPT");
//        // Sex
//        utility += attributes.get("female") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.female", 0.);
//        // Age
//        utility += attributes.get("age_0_18")  * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_1", 0.);
//        utility += attributes.get("age_19_29") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_2", 0.);
//        utility += attributes.get("age_30_49") * 0;
//        utility += (attributes.get("age_50_59") + attributes.get("age_60_69")) * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_45", 0.);
//        utility += attributes.get("age_70") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.age_gr_6", 0.);
//        // Mobility restriction
//        utility += attributes.get("mobilityRestricted") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mobilityRestricted", 0.);
//        // Household size
//        utility += attributes.get("averageHouseholdSize") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.size", 0.);
//        // Household Children
//        utility += attributes.get("hasChildren") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.children", 0.);
//
//        // Household BBSR Region Type
//        RegioStaR7 regionType = dataSet.getZones().get(person.getHousehold().getLocation().getZoneId()).getRegioStaR7Type();
//
//        if (regionType.equals(RegioStaR7.URBAN_METROPOLIS) || regionType.equals(RegioStaR7.URBAN_REGIOPOLIS)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_12", 0.);
//        } else if (regionType.equals(RegioStaR7.URBAN_MEDIUM_SIZED_CITY) || regionType.equals(RegioStaR7.URBAN_PROVINCIAL)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_34", 0.);
//        } else if (regionType.equals(RegioStaR7.RURAL_CENTRAL_CITY) || regionType.equals(RegioStaR7.RURAL_URBAN_AREA)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.regionType_56", 0.);
//        } else {
//            utility += 0;
//        }
//
//        // Urban or rural
//        RegioStaR2 regionType2 = dataSet.getZones().get(person.getHousehold().getLocation().getZoneId()).getRegioStaR2Type();
//        if (regionType2.equals(RegioStaR2.URBAN)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("hh.urban", 0.);
//        } else {
//            utility += 0;
//        }
//
//        //generalized costs
//        EnumMap<Mode, Double> generalizedCosts = calculateGeneralizedCostsForLogsum(purpose, tour, attributes);
//
//        double gc = generalizedCosts.get(mode);
//        utility += gc * purposeModeCoefficients.get(purpose).get(mode).get("gc");
//
//        //number of days with work or education tours
//        utility += attributes.get("numDaysWork") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_edu", 0.);
//        utility += attributes.get("numDaysEducation") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_work", 0.);
//        utility += attributes.get("numDaysMandatory") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("num_days_mand", 0.);
//
//        //tour purpose
//        if (purpose.equals(Purpose.EDUCATION)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("tour.purpose_education", 0.);
//        }
//
//        //number of stops on tour
//        if (tour.getActivities().size() >= 2) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).getOrDefault("tour.stops2orMore", 0.);
//        }
//
//        //habitual mode
//        utility += attributes.get("habitualMode_carDriver") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_carD", 0.);
//        utility += attributes.get("habitualMode_carPassenger") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_carP", 0.);
//        utility += attributes.get("habitualMode_bike") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_cycle", 0.);
//        utility += attributes.get("habitualMode_pt") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_PT", 0.);
//        utility += attributes.get("habitualMode_walk") * purposeModeCoefficients.get(purpose).get(mode).getOrDefault("p.mainCommuteMode_occupied_walk", 0.);
//
//        //to account for calibration factors
//        String region = getRegionForZone(tour.getActivities().get(tour.getActivities().firstKey()).getLocation().getZoneId());
//        if (region.equals("unknown")) {
//            region = "nonMuc";
//        }
//
//        if (purpose.equals(Purpose.WORK) || purpose.equals(Purpose.EDUCATION)) {
//            utility += purposeModeCoefficients.get(purpose).get(mode).get("calibration_" + purpose.toString().toLowerCase() + "_" +
//                    tour.getMainActivity().getDayOfWeek().toString().toLowerCase() + "_" + region.toLowerCase());
//        } else {
//            utility += purposeModeCoefficients.get(purpose).get(mode).get("calibration_" + tour.getMainActivity().getDayOfWeek().toString().toLowerCase() +
//                    "_" + region.toLowerCase());
//        }
//
//        if (runCalibration) {
//            utility += updatedCalibrationFactors.get(region).get(tour.getMainActivity().getPurpose()).get(tour.getMainActivity().getDayOfWeek()).get(mode);
//        }
//        return utility;
//    }

    private double calculateModeChoiceLogsumForThisMode(Person person, Tour tour, Purpose purpose, Mode mode, Map<String, Double> attributes) {

        // Intercept
        double utility =
                purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .get("INTERCEPT");

        // Sex
        utility +=
                attributes.get("female")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.female",
                                0.);

        // Age
        utility +=
                attributes.get("age_0_18")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.age_gr_1",
                                0.);

        utility +=
                attributes.get("age_19_29")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.age_gr_2",
                                0.);

        utility +=
                attributes.get("age_30_49")
                        * 0;

        utility +=
                (attributes.get("age_50_59")
                        + attributes.get("age_60_69"))
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.age_gr_45",
                                0.);

        utility +=
                attributes.get("age_70")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.age_gr_6",
                                0.);

        // Mobility restriction
        utility +=
                attributes.get("mobilityRestricted")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mobilityRestricted",
                                0.);

        // Household size
        utility +=
                attributes.get("averageHouseholdSize")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "hh.size",
                                0.);

        // Household Children
        utility +=
                attributes.get("hasChildren")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "hh.children",
                                0.);


        // Household BBSR Region Type
        RegioStaR7 regionType =
                dataSet.getZones()
                        .get(person.getHousehold()
                                .getLocation()
                                .getZoneId())
                        .getRegioStaR7Type();

        if (regionType.equals(RegioStaR7.URBAN_METROPOLIS)
                || regionType.equals(RegioStaR7.URBAN_REGIOPOLIS)) {

            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault(
                            "hh.regionType_12",
                            0.);

        } else if (regionType.equals(RegioStaR7.URBAN_MEDIUM_SIZED_CITY)
                || regionType.equals(RegioStaR7.URBAN_PROVINCIAL)) {

            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault(
                            "hh.regionType_34",
                            0.);

        } else if (regionType.equals(RegioStaR7.RURAL_CENTRAL_CITY)
                || regionType.equals(RegioStaR7.RURAL_URBAN_AREA)) {

            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault(
                            "hh.regionType_56",
                            0.);

        } else {
            utility += 0;
        }


        // Urban or rural
        RegioStaR2 regionType2 =
                dataSet.getZones()
                        .get(person.getHousehold()
                                .getLocation()
                                .getZoneId())
                        .getRegioStaR2Type();

        if (regionType2.equals(RegioStaR2.URBAN)) {

            utility += purposeModeCoefficients
                    .get(purpose)
                    .get(mode)
                    .getOrDefault(
                            "hh.urban",
                            0.);

        } else {
            utility += 0;
        }


        // generalized costs
        EnumMap<Mode, Double> generalizedCosts =
                calculateGeneralizedCostsForLogsum(
                        purpose,
                        tour,
                        attributes);

        double gc =
                generalizedCosts.get(mode);

        utility +=
                gc
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .get("gc");


        // number of days with work or education tours
        utility +=
                attributes.get("numDaysWork")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "num_days_edu",
                                0.);

        utility +=
                attributes.get("numDaysEducation")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "num_days_work",
                                0.);

        utility +=
                attributes.get("numDaysMandatory")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "num_days_mand",
                                0.);


        // tour purpose
        if (purpose.equals(Purpose.EDUCATION)) {

            utility +=
                    purposeModeCoefficients
                            .get(purpose)
                            .get(mode)
                            .getOrDefault(
                                    "tour.purpose_education",
                                    0.);
        }


        // number of stops on tour
        if (tour.getActivities().size() >= 2) {

            utility +=
                    purposeModeCoefficients
                            .get(purpose)
                            .get(mode)
                            .getOrDefault(
                                    "tour.stops2orMore",
                                    0.);
        }


        // habitual mode
        utility +=
                attributes.get("habitualMode_carDriver")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_carD",
                                0.);

        utility +=
                attributes.get("habitualMode_carPassenger")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_carP",
                                0.);

        utility +=
                attributes.get("habitualMode_bike")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_cycle",
                                0.);

        utility +=
                attributes.get("habitualMode_pt")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_PT",
                                0.);

        utility +=
                attributes.get("habitualMode_walk")
                        * purposeModeCoefficients
                        .get(purpose)
                        .get(mode)
                        .getOrDefault(
                                "p.mainCommuteMode_occupied_walk",
                                0.);


        // to account for calibration factors
        String region =
                getRegionForZone(
                        tour.getActivities()
                                .get(
                                        tour.getActivities()
                                                .firstKey())
                                .getLocation()
                                .getZoneId());

        if (region.equals("unknown")) {
            region = "nonMuc";
        }


        if (purpose.equals(Purpose.WORK)) {

            // NEW WORK SEGMENTATION
            Occupation occupation =
                    person.getOccupation();

            RemoteWorkable remoteWorkable =
                    getRemoteWorkable(person);

            RegioStaR2 regioStaR2 =
                    dataSet.getZones()
                            .get(person.getHousehold()
                                    .getLocation()
                                    .getZoneId())
                            .getRegioStaR2Type();

            utility +=
                    updatedWorkCalibrationFactors
                            .get(occupation)
                            .get(remoteWorkable)
                            .get(regioStaR2)
                            .get(mode);

        } else {

            // EDUCATION + DISCRETIONARY
            // Keep the old calibration logic.

            if (purpose.equals(Purpose.EDUCATION)) {

                utility +=
                        purposeModeCoefficients
                                .get(purpose)
                                .get(mode)
                                .get(
                                        "calibration_"
                                                + purpose
                                                .toString()
                                                .toLowerCase()
                                                + "_"
                                                + tour
                                                .getMainActivity()
                                                .getDayOfWeek()
                                                .toString()
                                                .toLowerCase()
                                                + "_"
                                                + region.toLowerCase());

            } else {

                utility +=
                        purposeModeCoefficients
                                .get(purpose)
                                .get(mode)
                                .get(
                                        "calibration_"
                                                + tour
                                                .getMainActivity()
                                                .getDayOfWeek()
                                                .toString()
                                                .toLowerCase()
                                                + "_"
                                                + region.toLowerCase());
            }
        }

        return utility;
    }

    //calculates generalized costs
    private EnumMap<Mode, Double> calculateGeneralizedCosts(Purpose purpose, Household household, Tour tour) {

        double travelDistanceAuto = 0;

        for (Leg leg : tour.getLegs().values()) {
            travelDistanceAuto += dataSet.getTravelDistances().getTravelDistanceInMeters(leg.getPreviousActivity().getLocation(),
                    leg.getNextActivity().getLocation(), Mode.UNKNOWN, leg.getPreviousActivity().getEndTime_min());
        }

        travelDistanceAuto = travelDistanceAuto / 1000.;

        EnumMap<Mode, Double> tourTravelTimes = new EnumMap<Mode, Double>(Mode.class);
        for (Mode mode : Mode.getModes()) {
            if (mode == Mode.CAR_DRIVER || mode == Mode.CAR_PASSENGER) {
                double travelTimeCarDriver = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelTimeCarDriver += dataSet.getTravelTimes().getTravelTimeInMinutes(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), Mode.CAR_DRIVER, leg.getPreviousActivity().getEndTime_min());
                }
                tourTravelTimes.put(mode, travelTimeCarDriver);
            } else if (mode == Mode.WALK || mode == Mode.BIKE) {
                double travelDistanceMode = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelDistanceMode += dataSet.getTravelDistances().getTravelDistanceInMeters(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), Mode.UNKNOWN, leg.getPreviousActivity().getEndTime_min());
                }

                double travelTimeMode = (travelDistanceMode / 1000.) / (mode == Mode.WALK ? SPEED_WALK_KMH : SPEED_BICYCLE_KMH) * 60.;
                tourTravelTimes.put(mode, travelTimeMode);

            } else {
                double travelTimeMode = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelTimeMode += dataSet.getTravelTimes().getTravelTimeInMinutes(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), mode, leg.getPreviousActivity().getEndTime_min());
                }
                tourTravelTimes.put(mode, travelTimeMode);
            }
        }

        double gcAutoD;
        double gcAutoP;
        double gcBus;
        double gcTrain;
        double gcTramMetro;

        double monthlyIncome_EUR = household.getPersons().stream().mapToInt(Person::getMonthlyIncome_eur).sum();

        //todo. Add VOT table with the coefficients and rename
        if (monthlyIncome_EUR <= 1500) {
            gcAutoD = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_less_or_equal_income_4");
            gcAutoP = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_less_or_equal_income_4");
            gcBus = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_less_or_equal_income_4");
            gcTrain = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_less_or_equal_income_4");
            gcTramMetro = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_less_or_equal_income_4");
        } else if (monthlyIncome_EUR <= 5000) {
            gcAutoD = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_income_5_to_10");
            gcAutoP = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_income_5_to_10");
            gcBus = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_income_5_to_10");
            gcTrain = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_income_5_to_10");
            gcTramMetro = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_income_5_to_10");
        } else {
            gcAutoD = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_income_greater_10");
            gcAutoP = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_income_greater_10");
            gcBus = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_income_greater_10");
            gcTrain = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_income_greater_10");
            gcTramMetro = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_income_greater_10");
        }

        EnumMap<Mode, Double> generalizedCosts = new EnumMap<Mode, Double>(Mode.class);

        if (purpose.equals(Purpose.ACCOMPANY)) {
            generalizedCosts.put(Mode.CAR_DRIVER, Math.log(gcAutoD));
            generalizedCosts.put(Mode.CAR_PASSENGER, Math.log(gcAutoP));
            generalizedCosts.put(Mode.BIKE, Math.log(tourTravelTimes.get(Mode.BIKE)));
            generalizedCosts.put(Mode.BUS, Math.log(gcBus));
            generalizedCosts.put(Mode.TRAIN, Math.log(gcTrain));
            generalizedCosts.put(Mode.TRAM_METRO, Math.log(gcTramMetro));
            generalizedCosts.put(Mode.WALK, Math.log(tourTravelTimes.get(Mode.WALK)));
        } else {
            generalizedCosts.put(Mode.CAR_DRIVER, gcAutoD);
            generalizedCosts.put(Mode.CAR_PASSENGER, gcAutoP);
            generalizedCosts.put(Mode.BIKE, tourTravelTimes.get(Mode.BIKE));
            generalizedCosts.put(Mode.BUS, gcBus);
            generalizedCosts.put(Mode.TRAIN, gcTrain);
            generalizedCosts.put(Mode.TRAM_METRO, gcTramMetro);
            generalizedCosts.put(Mode.WALK, tourTravelTimes.get(Mode.WALK));
        }
        return generalizedCosts;
    }

    private EnumMap<Mode, Double> calculateGeneralizedCostsForLogsum(Purpose purpose, Tour tour, Map<String, Double> attributes) {

        double travelDistanceAuto = 0;

        for (Leg leg : tour.getLegs().values()) {
            travelDistanceAuto += dataSet.getTravelDistances().getTravelDistanceInMeters(leg.getPreviousActivity().getLocation(),
                    leg.getNextActivity().getLocation(), Mode.UNKNOWN, leg.getPreviousActivity().getEndTime_min());
        }

        travelDistanceAuto = travelDistanceAuto / 1000.;

        EnumMap<Mode, Double> tourTravelTimes = new EnumMap<Mode, Double>(Mode.class);
        for (Mode mode : Mode.getModes()) {
            if (mode == Mode.CAR_DRIVER || mode == Mode.CAR_PASSENGER) {
                double travelTimeCarDriver = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelTimeCarDriver += dataSet.getTravelTimes().getTravelTimeInMinutes(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), Mode.CAR_DRIVER, leg.getPreviousActivity().getEndTime_min());
                }
                tourTravelTimes.put(mode, travelTimeCarDriver);
            } else if (mode == Mode.WALK || mode == Mode.BIKE) {
                double travelDistanceMode = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelDistanceMode += dataSet.getTravelDistances().getTravelDistanceInMeters(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), Mode.UNKNOWN, leg.getPreviousActivity().getEndTime_min());
                }

                double travelTimeMode = (travelDistanceMode / 1000.) / (mode == Mode.WALK ? SPEED_WALK_KMH : SPEED_BICYCLE_KMH) * 60.;
                tourTravelTimes.put(mode, travelTimeMode);

            } else {
                double travelTimeMode = 0;
                for (Leg leg : tour.getLegs().values()) {
                    travelTimeMode += dataSet.getTravelTimes().getTravelTimeInMinutes(leg.getPreviousActivity().getLocation(),
                            leg.getNextActivity().getLocation(), mode, leg.getPreviousActivity().getEndTime_min());
                }
                tourTravelTimes.put(mode, travelTimeMode);
            }
        }

        double gcAutoD_0_1500 = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_less_or_equal_income_4");
        double gcAutoP_0_1500 = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_less_or_equal_income_4");
        double gcBus_0_1500 = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_less_or_equal_income_4");
        double gcTrain_0_1500 = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_less_or_equal_income_4");
        double gcTramMetro_0_1500 = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_less_or_equal_income_4");

        double gcAutoD_1501_5600 = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_income_5_to_10");
        double gcAutoP_1501_5600 = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_income_5_to_10");
        double gcBus_1501_5600 = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_income_5_to_10");
        double gcTrain_1501_5600 = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_income_5_to_10");
        double gcTramMetro_1501_5600 = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_income_5_to_10");

        double gcAutoD_5601 = tourTravelTimes.get(Mode.CAR_DRIVER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_DRIVER).get("vot_income_greater_10");
        double gcAutoP_5601 = tourTravelTimes.get(Mode.CAR_PASSENGER) + (travelDistanceAuto * fuelCostEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.CAR_PASSENGER).get("vot_income_greater_10");
        double gcBus_5601 = tourTravelTimes.get(Mode.BUS) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.BUS).get("vot_income_greater_10");
        double gcTrain_5601 = tourTravelTimes.get(Mode.TRAIN) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAIN).get("vot_income_greater_10");
        double gcTramMetro_5601 = tourTravelTimes.get(Mode.TRAM_METRO) + (travelDistanceAuto * transitFareEurosPerKm) / purposeModeCoefficients.get(purpose).get(Mode.TRAM_METRO).get("vot_income_greater_10");

        double gcAutoD = attributes.get("income_0_1500") * gcAutoD_0_1500 + attributes.get("income_1501_5600") * gcAutoD_1501_5600 + attributes.get("income_5601") * gcAutoD_5601;
        double gcAutoP = attributes.get("income_0_1500") * gcAutoP_0_1500 + attributes.get("income_1501_5600") * gcAutoP_1501_5600 + attributes.get("income_5601") * gcAutoP_5601;
        double gcBus = attributes.get("income_0_1500") * gcBus_0_1500 + attributes.get("income_1501_5600") * gcBus_1501_5600 + attributes.get("income_5601") * gcBus_5601;
        double gcTrain = attributes.get("income_0_1500") * gcTrain_0_1500 + attributes.get("income_1501_5600") * gcTrain_1501_5600 + attributes.get("income_5601") * gcTrain_5601;
        double gcTramMetro = attributes.get("income_0_1500") * gcTramMetro_0_1500 + attributes.get("income_1501_5600") * gcTramMetro_1501_5600 + attributes.get("income_5601") * gcTramMetro_5601;

        EnumMap<Mode, Double> generalizedCosts = new EnumMap<Mode, Double>(Mode.class);

        if (purpose.equals(Purpose.ACCOMPANY)) {
            generalizedCosts.put(Mode.CAR_DRIVER, Math.log(gcAutoD));
            generalizedCosts.put(Mode.CAR_PASSENGER, Math.log(gcAutoP));
            generalizedCosts.put(Mode.BIKE, Math.log(tourTravelTimes.get(Mode.BIKE)));
            generalizedCosts.put(Mode.BUS, Math.log(gcBus));
            generalizedCosts.put(Mode.TRAIN, Math.log(gcTrain));
            generalizedCosts.put(Mode.TRAM_METRO, Math.log(gcTramMetro));
            generalizedCosts.put(Mode.WALK, Math.log(tourTravelTimes.get(Mode.WALK)));
        } else {
            generalizedCosts.put(Mode.CAR_DRIVER, gcAutoD);
            generalizedCosts.put(Mode.CAR_PASSENGER, gcAutoP);
            generalizedCosts.put(Mode.BIKE, tourTravelTimes.get(Mode.BIKE));
            generalizedCosts.put(Mode.BUS, gcBus);
            generalizedCosts.put(Mode.TRAIN, gcTrain);
            generalizedCosts.put(Mode.TRAM_METRO, gcTramMetro);
            generalizedCosts.put(Mode.WALK, tourTravelTimes.get(Mode.WALK));
        }
        return generalizedCosts;
    }

    public void updateWorkCalibrationFactor(Map<Occupation, Map<RemoteWorkable, Map<RegioStaR2, Map<Mode, Double>>>> newCalibrationFactors) {

        for (Occupation occupation :
                newCalibrationFactors.keySet()) {

            for (RemoteWorkable remoteWorkable :
                    newCalibrationFactors
                            .get(occupation)
                            .keySet()) {

                for (RegioStaR2 regioStaR2 :
                        newCalibrationFactors
                                .get(occupation)
                                .get(remoteWorkable)
                                .keySet()) {

                    for (Mode mode :
                            Mode.getModes()) {

                        double calibrationFactorFromLastIteration =
                                updatedWorkCalibrationFactors
                                        .get(occupation)
                                        .get(remoteWorkable)
                                        .get(regioStaR2)
                                        .get(mode);

                        double updatedCalibrationFactor =
                                calibrationFactorFromLastIteration
                                        + newCalibrationFactors
                                        .get(occupation)
                                        .get(remoteWorkable)
                                        .get(regioStaR2)
                                        .get(mode);

                        updatedWorkCalibrationFactors
                                .get(occupation)
                                .get(remoteWorkable)
                                .get(regioStaR2)
                                .put(
                                        mode,
                                        updatedCalibrationFactor);

                        logger.info(
                                "Calibration factor for WORK | "
                                        + occupation
                                        + " | "
                                        + remoteWorkable
                                        + " | "
                                        + regioStaR2
                                        + " | "
                                        + mode
                                        + " : "
                                        + updatedCalibrationFactor);
                    }
                }
            }
        }
    }

    public void updateDiscretionaryCalibrationFactor(Map<Purpose, Map<Occupation, Map<String, Map<RegioStaR2, Map<Mode, Double>>>>> newCalibrationFactors) {

        for (Purpose purpose :
                newCalibrationFactors.keySet()) {

            for (Occupation occupation :
                    newCalibrationFactors
                            .get(purpose)
                            .keySet()) {

                for (String dayType :
                        newCalibrationFactors
                                .get(purpose)
                                .get(occupation)
                                .keySet()) {

                    for (RegioStaR2 regioStaR2 :
                            newCalibrationFactors
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .keySet()) {

                        for (Mode mode : Mode.getModes()) {

                            double calibrationFactorFromLastIteration =
                                    updatedDiscretionaryCalibrationFactors
                                            .get(purpose)
                                            .get(occupation)
                                            .get(dayType)
                                            .get(regioStaR2)
                                            .get(mode);

                            double updatedCalibrationFactor =
                                    calibrationFactorFromLastIteration
                                            + newCalibrationFactors
                                            .get(purpose)
                                            .get(occupation)
                                            .get(dayType)
                                            .get(regioStaR2)
                                            .get(mode);

                            updatedDiscretionaryCalibrationFactors
                                    .get(purpose)
                                    .get(occupation)
                                    .get(dayType)
                                    .get(regioStaR2)
                                    .put(
                                            mode,
                                            updatedCalibrationFactor);

                            logger.info(
                                    "Calibration factor for DISCRETIONARY | "
                                            + purpose
                                            + " | "
                                            + occupation
                                            + " | "
                                            + dayType
                                            + " | "
                                            + regioStaR2
                                            + " | "
                                            + mode
                                            + " : "
                                            + updatedCalibrationFactor);
                        }
                    }
                }
            }
        }
    }

    public Map<Purpose, Map<Mode, Map<String, Double>>>
    obtainCoefficientsTable() {

        return this.purposeModeCoefficients;
    }

    public Map<Occupation, Map<RemoteWorkable, Map<RegioStaR2, Map<Mode, Double>>>> getUpdatedWorkCalibrationFactors() {
        return updatedWorkCalibrationFactors;
    }

    public Map<Purpose, Map<Occupation, Map<String, Map<RegioStaR2, Map<Mode, Double>>>>> getUpdatedDiscretionaryCalibrationFactors() {

        return updatedDiscretionaryCalibrationFactors;
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

    private void loadCalibrationFactors() {

        initializeCalibrationFactors();

        if (Files.isRegularFile(tourModeCalibrationOutputPath)) {

            loadWorkCalibrationFactors(
                    tourModeCalibrationOutputPath
            );
        }

        for (Purpose purpose : Purpose.getDiscretionaryPurposes()) {

            Path calibrationFile =
                    Path.of(
                                    AbitResources.instance.getString(
                                            "tour.mode.coef.output"
                                    )
                            )
                            .resolve(
                                    purpose.toString().toLowerCase()
                                            + "Mode_nestedLogit_calibrated.csv"
                            );

            if (Files.isRegularFile(calibrationFile)) {

                loadDiscretionaryCalibrationFactors(
                        calibrationFile,
                        purpose
                );
            }
        }
    }

    private void loadWorkCalibrationFactors(Path calibrationPath) {

        try (BufferedReader reader =
                     Files.newBufferedReader(calibrationPath)) {

            String header = reader.readLine();

            if (header == null) {
                throw new IllegalStateException(
                        "Calibration file is empty: "
                                + calibrationPath
                );
            }

            String[] columns =
                    header.split(",", -1);

            Map<Mode, Integer> modeColumns =
                    new HashMap<>();

            for (int i = 1; i < columns.length; i++) {

                modeColumns.put(
                        Mode.valueOf(
                                columns[i]
                                        .trim()
                                        .toUpperCase()
                        ),
                        i
                );
            }

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] values =
                        line.split(",", -1);

                String variable =
                        values[0].trim();

                if (!variable.startsWith("calibration_work_")) {
                    continue;
                }

                String name =
                        variable.substring(
                                "calibration_work_".length()
                        );

                String[] segment =
                        name.split("_");

                if (segment.length != 3) {
                    continue;
                }

                Occupation occupation =
                        Occupation.valueOf(
                                segment[0].toUpperCase()
                        );

                RemoteWorkable remoteWorkable =
                        RemoteWorkable.valueOf(
                                segment[1].toUpperCase()
                        );

                RegioStaR2 regioStaR2 =
                        RegioStaR2.valueOf(
                                segment[2].toUpperCase()
                        );

                for (Mode mode : Mode.getModes()) {

                    Integer column =
                            modeColumns.get(mode);

                    if (column == null
                            || column >= values.length
                            || values[column].trim().isEmpty()) {
                        continue;
                    }

                    double value =
                            Double.parseDouble(
                                    values[column].trim()
                            );

                    updatedWorkCalibrationFactors
                            .get(occupation)
                            .get(remoteWorkable)
                            .get(regioStaR2)
                            .put(
                                    mode,
                                    value
                            );
                }
            }

        } catch (IOException e) {

            throw new IllegalStateException(
                    "Could not read WORK calibration file: "
                            + calibrationPath,
                    e
            );
        }
    }

    private void loadDiscretionaryCalibrationFactors(Path calibrationPath, Purpose purpose) {

        try (BufferedReader reader =
                     Files.newBufferedReader(calibrationPath)) {

            String header = reader.readLine();

            if (header == null) {
                throw new IllegalStateException(
                        "Calibration file is empty: "
                                + calibrationPath);
            }

            String[] columns =
                    header.split(",", -1);

            Map<Mode, Integer> modeColumns =
                    new HashMap<>();

            for (int i = 1; i < columns.length; i++) {

                modeColumns.put(
                        Mode.valueOf(
                                columns[i]
                                        .trim()
                                        .toUpperCase()),
                        i);
            }

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] values =
                        line.split(",", -1);

                String variable =
                        values[0].trim();

                if (!variable.startsWith(
                        "calibration_")) {
                    continue;
                }

                String name =
                        variable.substring(
                                "calibration_".length());

                String[] segment =
                        name.split("_");

                /*
                 * Expected:
                 *
                 * calibration_<purpose>_<occupation>_<dayType>_<RegioStaR2>
                 *
                 * dayType itself contains "_":
                 *
                 * NO_WORKING_DAY
                 * WFH_DAY
                 * WFW_DAY
                 *
                 * Therefore we cannot simply expect
                 * segment.length == 4.
                 */

                if (segment.length < 4) {
                    continue;
                }


                // -------------------------------------------------
                // occupation
                // -------------------------------------------------

                Occupation occupation =
                        Occupation.valueOf(
                                segment[1].toUpperCase());


                // -------------------------------------------------
                // RegioStaR2 is always the LAST segment
                // -------------------------------------------------

                RegioStaR2 regioStaR2 =
                        RegioStaR2.valueOf(
                                segment[segment.length - 1]
                                        .toUpperCase());


                // -------------------------------------------------
                // dayType consists of everything between
                // occupation and RegioStaR2
                // -------------------------------------------------

                StringBuilder dayTypeBuilder =
                        new StringBuilder();

                for (int i = 2;
                     i < segment.length - 1;
                     i++) {

                    if (dayTypeBuilder.length() > 0) {
                        dayTypeBuilder.append("_");
                    }

                    dayTypeBuilder.append(
                            segment[i]);
                }

                String dayType =
                        dayTypeBuilder
                                .toString()
                                .toUpperCase();


                // -------------------------------------------------
                // Check valid day type
                // -------------------------------------------------

                if (!dayType.equals("NO_WORKING_DAY")
                        && !dayType.equals("WFH_DAY")
                        && !dayType.equals("WFW_DAY")) {

                    logger.warn(
                            "Unknown discretionary day type '"
                                    + dayType
                                    + "' in calibration file: "
                                    + calibrationPath);

                    continue;
                }


                // -------------------------------------------------
                // Read calibration factors
                // -------------------------------------------------

                for (Mode mode : Mode.getModes()) {

                    Integer column =
                            modeColumns.get(mode);

                    if (column == null
                            || column >= values.length
                            || values[column]
                            .trim()
                            .isEmpty()) {

                        continue;
                    }

                    double value =
                            Double.parseDouble(
                                    values[column].trim());


                    updatedDiscretionaryCalibrationFactors
                            .get(purpose)
                            .get(occupation)
                            .get(dayType)
                            .get(regioStaR2)
                            .put(
                                    mode,
                                    value);
                }
            }

        } catch (IOException e) {

            throw new IllegalStateException(
                    "Could not read discretionary calibration file: "
                            + calibrationPath,
                    e);
        }
    }

}
