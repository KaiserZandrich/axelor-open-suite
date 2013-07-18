package com.axelor.apps.supplychain.service;

import java.math.BigDecimal;
import java.util.List;

import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.IAdministration;
import com.axelor.apps.base.db.IProduct;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.TrackingNumber;
import com.axelor.apps.base.db.TrackingNumberConfiguration;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.base.service.administration.GeneralService;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.supplychain.db.ILocation;
import com.axelor.apps.supplychain.db.IStockMove;
import com.axelor.apps.supplychain.db.Location;
import com.axelor.apps.supplychain.db.LocationLine;
import com.axelor.apps.supplychain.db.StockMove;
import com.axelor.apps.supplychain.db.StockMoveLine;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class StockMoveService {
	
	private static final Logger LOG = LoggerFactory.getLogger(StockMoveService.class); 

	@Inject
	private SequenceService sequenceService;
	
	private LocalDate today;
	private String exceptionMsg;
	
	@Inject
	public StockMoveService() {

		this.today = GeneralService.getTodayDate();
		this.exceptionMsg = GeneralService.getExceptionAccountingMsg();
		
	}
	
	/**
	 * Méthode permettant d'obtenir la séquence du StockMove.
	 * @param stockMoveType Type de mouvement de stock
	 * @param company la société
	 * @return la chaine contenant la séquence du StockMove
	 * @throws AxelorException Aucune séquence de StockMove n'a été configurée
	 */
	public String getSequenceStockMove(int stockMoveType, Company company) throws AxelorException {

		String ref = "";
		
		switch(stockMoveType)  {
			case IStockMove.INTERNAL:
				ref = sequenceService.getSequence(IAdministration.INTERNAL, company, false);
				if (ref == null)  {
					throw new AxelorException(String.format("%s Aucune séquence configurée pour les livraisons de stock pour la société %s",
							exceptionMsg, company.getName()), IException.CONFIGURATION_ERROR);
				}
				break;
				
			case IStockMove.INCOMING:
				ref = sequenceService.getSequence(IAdministration.INCOMING, company, false);
				if (ref == null)  {
					throw new AxelorException(String.format("%s Aucune séquence configurée pour les livraisons de stock pour la société %s",
							exceptionMsg, company.getName()), IException.CONFIGURATION_ERROR);
				}
				break;
				
			case IStockMove.OUTGOING:
				ref = sequenceService.getSequence(IAdministration.OUTGOING, company, false);
				if (ref == null)  {
					throw new AxelorException(String.format("%s Aucune séquence configurée pour les livraisons de stock pour la société %s",
							exceptionMsg, company.getName()), IException.CONFIGURATION_ERROR);
				}
				break;
			
			default:
				throw new AxelorException(String.format("%s Type de mouvement de stock non déterminé ",
						exceptionMsg, company.getName()), IException.CONFIGURATION_ERROR);
		
		}
		
		return ref;
	}
	
	/**
	 * Méthode générique permettant de créer un StockMove.
	 * @param toAddress l'adresse destination
	 * @param company la société
	 * @param clientPartner le tier client
	 * @param refSequence la séquence du StockMove
	 * @return l'objet StockMove
	 * @throws AxelorException Aucune séquence de StockMove (Livraison) n'a été configurée
	 */
	public StockMove createStocksMoves(Address toAddress, Company company, Partner clientPartner, Location fromLocation, Location toLocation) throws AxelorException {

		return this.createStocksMoves(toAddress, company, clientPartner, fromLocation, toLocation, this.today, this.today);
	}
	
	
	/**
	 * Méthode générique permettant de créer un StockMove.
	 * @param toAddress l'adresse destination
	 * @param company la société
	 * @param clientPartner le tier client
	 * @param refSequence la séquence du StockMove
	 * @return l'objet StockMove
	 * @throws AxelorException Aucune séquence de StockMove (Livraison) n'a été configurée
	 */
	public StockMove createStocksMoves(Address toAddress, Company company, Partner clientPartner, Location fromLocation, Location toLocation, LocalDate realDate, LocalDate estimatedDate) throws AxelorException {

		StockMove stockMove = new StockMove();
		stockMove.setToAddress(toAddress);
		stockMove.setCompany(company);
		stockMove.setStatusSelect(IStockMove.DRAFT);
		stockMove.setRealDate(realDate);
		stockMove.setEstimatedDate(estimatedDate);
		stockMove.setPartner(clientPartner);
		stockMove.setFromLocation(fromLocation);
		stockMove.setToLocation(toLocation);
		
		return stockMove;
	}
	
	
	public int getStockMoveType(Location fromLocation, Location toLocation)  {
		
		if(fromLocation.getTypeSelect() == ILocation.INTERNAL && toLocation.getTypeSelect() == ILocation.INTERNAL) {
			return IStockMove.INTERNAL;
		}
		else if(fromLocation.getTypeSelect() != ILocation.INTERNAL && toLocation.getTypeSelect() == ILocation.INTERNAL) {	
			return IStockMove.INCOMING;
		}
		else if(fromLocation.getTypeSelect() == ILocation.INTERNAL && toLocation.getTypeSelect() != ILocation.INTERNAL) {
			return IStockMove.OUTGOING;
		}
		return 0;
	}

	
	public void validate(StockMove stockMove) throws AxelorException  {
		
		this.plan(stockMove);
		this.realize(stockMove);
		
	}
 	
	
	
	/**
	 * Méthode générique permettant de créer une ligne de mouvement de stock en gérant les numéros de suivi en fonction du type d'opération.
	 * @param product le produit
	 * @param quantity la quantité
	 * @param parent le StockMove parent
	 * @param type
	 * 1 : Sales
	 * 2 : Purchases
	 * 3 : Productions
	 * 
	 * @return l'objet StockMoveLine
	 * @throws AxelorException 
	 */
	public StockMoveLine createStockMoveLine(Product product, BigDecimal quantity, Unit unit, BigDecimal price, StockMove stockMove, int type ) throws AxelorException {

		if(product.getApplicationTypeSelect() == IProduct.PRODUCT_TYPE) {
			
			StockMoveLine stockMoveLine = this.createStockMoveLine(product, quantity, unit, price, stockMove, null);
			
			TrackingNumberConfiguration trackingNumberConfiguration = product.getTrackingNumberConfiguration();
			if(trackingNumberConfiguration != null)  {
				
				switch (type) {
					case 1:
						if(trackingNumberConfiguration.getIsSaleTrackingManaged())  {
							if(trackingNumberConfiguration.getGenerateSaleAutoTrackingNbr())  {
								// Générer numéro de série si case cochée
								stockMoveLine.setTrackingNumber(
										this.getTrackingNumber(product, trackingNumberConfiguration.getSaleQtyByTracking(), stockMove.getCompany(), stockMove.getEstimatedDate()));
							}
						}
						else if(trackingNumberConfiguration.getIsPurchaseTrackingManaged() || trackingNumberConfiguration.getIsProductionTrackingManaged())  {
							// Rechercher le numéro de suivi d'apèrs FIFO/LIFO
							this.assignTrackingNumber(stockMoveLine, product, stockMove.getFromLocation());
						}
						break;
					case 2:
						if(trackingNumberConfiguration.getIsPurchaseTrackingManaged() && trackingNumberConfiguration.getGeneratePurchaseAutoTrackingNbr())  {
							// Générer numéro de série si case cochée
							stockMoveLine.setTrackingNumber(
									this.getTrackingNumber(product, trackingNumberConfiguration.getPurchaseQtyByTracking(), stockMove.getCompany(), stockMove.getEstimatedDate()));
						}
						break;
					case 3:
						if(trackingNumberConfiguration.getIsProductionTrackingManaged() && trackingNumberConfiguration.getGenerateProductionAutoTrackingNbr())  {
							// Générer numéro de série si case cochée
							stockMoveLine.setTrackingNumber(
									this.getTrackingNumber(product, trackingNumberConfiguration.getProductionQtyByTracking(), stockMove.getCompany(), stockMove.getEstimatedDate()));
						}
						break;
	
					default:
						break;
				}
			}
			
			return stockMoveLine;
		}
		return null;
	}
	
	
	/**
	 * Méthode générique permettant de créer une ligne de mouvement de stock
	 * @param product
	 * @param quantity
	 * @param unit
	 * @param price
	 * @param stockMove
	 * @param trackingNumber
	 * @return
	 * @throws AxelorException
	 */
	public StockMoveLine createStockMoveLine(Product product, BigDecimal quantity, Unit unit, BigDecimal price, StockMove stockMove, TrackingNumber trackingNumber ) throws AxelorException {

		StockMoveLine stockMoveLine = new StockMoveLine();
		stockMoveLine.setStockMove(stockMove);
		stockMoveLine.setProduct(product);
		stockMoveLine.setQty(quantity);
		stockMoveLine.setUnit(unit);
		stockMoveLine.setPrice(price);
		stockMoveLine.setTrackingNumber(trackingNumber);
		
		return stockMoveLine;
	}
	
	
	
	public TrackingNumber getTrackingNumber(Product product, int sizeOfLot, Company company, LocalDate date) throws AxelorException  {
		
		TrackingNumber trackingNumber = TrackingNumber.all().filter("self.product = ?1 and self.counter < ?2", product, sizeOfLot).fetchOne();
	
		if(trackingNumber == null)  {
			trackingNumber = this.createTrackingNumber(product, company, date);
		}
		
		trackingNumber.setCounter(trackingNumber.getCounter()+1);
		
		return trackingNumber;
		
	}
	
	
	public void assignTrackingNumber(StockMoveLine stockMoveLine, Product product, Location location) throws AxelorException  {
		
		
		for(LocationLine locationLine : this.getLocationLines(product, location))  {
			
			BigDecimal qty = locationLine.getFutureQty();
			if(stockMoveLine.getQty().compareTo(qty) == 1)  {
				this.splitStockMoveLine(stockMoveLine, qty, locationLine.getTrackingNumber());
			}
			else  {
				stockMoveLine.setTrackingNumber(locationLine.getTrackingNumber());
				break;
			}
			
		}
		
	}
	
	
	
	public List<LocationLine> getLocationLines(Product product, Location location) throws AxelorException  {
		
		List<LocationLine> locationLineList = LocationLine.all()
				.filter("self.product = ?1 AND self.futureQty > 0 AND self.trackingNumber IS NOT NULL AND self.detailsLocation = ?2"
						+this.getOrderMethod(product.getTrackingNumberConfiguration()), product, location).fetch();
		
		return locationLineList;
		
	}

	
	
	public StockMoveLine splitStockMoveLine(StockMoveLine stockMoveLine, BigDecimal qty, TrackingNumber trackingNumber) throws AxelorException  {
		
		StockMoveLine newStockMoveLine = this.createStockMoveLine(
				stockMoveLine.getProduct(), 
				qty, 
				stockMoveLine.getUnit(), 
				stockMoveLine.getPrice(), 
				stockMoveLine.getStockMove(), 
				trackingNumber);
		
		stockMoveLine.getStockMove().getStockMoveLineList().add(newStockMoveLine);
		
		stockMoveLine.setQty(stockMoveLine.getQty().subtract(qty));
	
		return newStockMoveLine;
	}
	
	
	public String getOrderMethod(TrackingNumberConfiguration trackingNumberConfiguration)  {
		switch (trackingNumberConfiguration.getSaleAutoTrackingNbrOrderSelect()) {
			case IProduct.FIFO:
				return " ORDER BY self.trackingNumber ASC";
				
			case IProduct.LIFO:
				return " ORDER BY self.trackingNumber DESC";
	
			default:
				return "";
		}
	}
	
	

	public TrackingNumber createTrackingNumber(Product product, Company company, LocalDate date) throws AxelorException  {
		
		TrackingNumber trackingNumber = new TrackingNumber();
		
		if(product.getIsPerishable())  {
			trackingNumber.setPerishableExpirationDate(date.plusMonths(product.getPerishableNbrOfMonths()));
		}
		if(product.getHasWarranty())  {
			trackingNumber.setWarrantyExpirationDate(date.plusMonths(product.getWarrantyNbrOfMonths()));
		}
		
		trackingNumber.setProduct(product);
		trackingNumber.setCounter(0);
		
		String seq = sequenceService.getSequence(IAdministration.PRODUCT_TRACKING_NUMBER, product, company, false);
		if (seq == null)  {
			throw new AxelorException(String.format("%s Aucune séquence configurée pour les Numéros de suivi pour la société %s et le produit %s ",
					exceptionMsg, company.getName(), product.getCode()), IException.CONFIGURATION_ERROR);
		}
		
		trackingNumber.setTrackingNumberSeq(seq);
		
		return trackingNumber;
	}
	
	
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void plan(StockMove stockMove) throws AxelorException  {
		
		LOG.debug("Plannification du mouvement de stock : {} ", new Object[] { stockMove.getStockMoveSeq() });
		
		Location fromLocation = stockMove.getFromLocation();
		Location toLocation = stockMove.getToLocation();
		
		if(fromLocation == null)  {
			throw new AxelorException(String.format("%s Aucun emplacement source selectionné pour le mouvement de stock %s",
					exceptionMsg, stockMove.getName()), IException.CONFIGURATION_ERROR);
		}
		if(toLocation == null)  {
			throw new AxelorException(String.format("%s Aucun emplacement destination selectionné pour le mouvement de stock %s",
					exceptionMsg, stockMove.getName()), IException.CONFIGURATION_ERROR);
		}
		
		// Set the type select
		if(stockMove.getTypeSelect() == null || stockMove.getTypeSelect() == 0)  {
			stockMove.setTypeSelect(this.getStockMoveType(fromLocation, toLocation));
		}

		
		if(stockMove.getTypeSelect() == IStockMove.OUTGOING)  {
			
		}
		
		// Set the sequence
		if(stockMove.getStockMoveSeq() == null || stockMove.getStockMoveSeq().isEmpty())  {
			stockMove.setStockMoveSeq(
					this.getSequenceStockMove(stockMove.getTypeSelect(), stockMove.getCompany()));
		}
		
		if(stockMove.getName() == null || stockMove.getName().isEmpty())  {
			stockMove.setName(stockMove.getStockMoveSeq());
		}
		
		this.updateLocations(
				fromLocation, 
				toLocation, 
				stockMove.getStatusSelect(), 
				IStockMove.PLANNED, 
				stockMove.getStockMoveLineList(),
				stockMove.getEstimatedDate());
		
		if(stockMove.getEstimatedDate() == null)  {
			stockMove.setEstimatedDate(this.today);
		}
		
		stockMove.setStatusSelect(IStockMove.PLANNED);
		
		stockMove.save();
		
	}
	
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void realize(StockMove stockMove) throws AxelorException  {
	
		LOG.debug("Réalisation du mouvement de stock : {} ", new Object[] { stockMove.getStockMoveSeq() });
		
		this.updateLocations(
				stockMove.getFromLocation(), 
				stockMove.getToLocation(), 
				stockMove.getStatusSelect(), 
				IStockMove.REALIZED, 
				stockMove.getStockMoveLineList(),
				stockMove.getEstimatedDate());
		
		stockMove.setStatusSelect(IStockMove.REALIZED);
		stockMove.setRealDate(this.today);
		stockMove.save();
	}
	
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void cancel(StockMove stockMove) throws AxelorException  {
	
		LOG.debug("Annulation du mouvement de stock : {} ", new Object[] { stockMove.getStockMoveSeq() });
		
		this.updateLocations(
				stockMove.getFromLocation(), 
				stockMove.getToLocation(), 
				stockMove.getStatusSelect(), 
				IStockMove.CANCELED, 
				stockMove.getStockMoveLineList(),
				stockMove.getEstimatedDate());
		
		stockMove.setStatusSelect(IStockMove.CANCELED);
		stockMove.setRealDate(this.today);
		stockMove.save();
	}
	
	
	public void updateLocations(Location fromLocation, Location toLocation, int fromStatus, int toStatus, List<StockMoveLine> stockMoveLineList, LocalDate lastFutureStockMoveDate) throws AxelorException  {
		
		for(StockMoveLine stockMoveLine : stockMoveLineList)  {
			
			Unit productUnit = stockMoveLine.getProduct().getUnit();
			Unit stockMoveLineUnit = stockMoveLine.getUnit();
			
			BigDecimal qty = stockMoveLine.getQty();
			if(!productUnit.equals(stockMoveLineUnit))  {
				qty = new UnitConversionService().convert(stockMoveLineUnit, productUnit, qty);
			}
			
			this.updateLocations(fromLocation, toLocation, stockMoveLine.getProduct(), qty, fromStatus, toStatus, lastFutureStockMoveDate, stockMoveLine.getTrackingNumber());
			
		}
		
	}
	
	
	public void updateLocations(Location fromLocation, Location toLocation, Product product, BigDecimal qty, int fromStatus, int toStatus, LocalDate 
			lastFutureStockMoveDate, TrackingNumber trackingNumber)  {
		
		switch(fromStatus)  {
			case IStockMove.PLANNED:
				this.updateLocation(fromLocation, product, qty, false, true, true, null, trackingNumber);
				this.updateLocation(toLocation, product, qty, false, true, false, null, trackingNumber);
				break;
				
			case IStockMove.REALIZED:
				this.updateLocation(fromLocation, product, qty, true, true, true, null, trackingNumber);
				this.updateLocation(toLocation, product, qty, true, true, false, null, trackingNumber);
				break;
			
			default:
				break;
		}
		
		switch(toStatus)  {
			case IStockMove.PLANNED:
				this.updateLocation(fromLocation, product, qty, false, true, false, lastFutureStockMoveDate, trackingNumber);
				this.updateLocation(toLocation, product, qty, false, true, true, lastFutureStockMoveDate, trackingNumber);
				break;
				
			case IStockMove.REALIZED:
				this.updateLocation(fromLocation, product, qty, true, true, false, null, trackingNumber);
				this.updateLocation(toLocation, product, qty, true, true, true, null, trackingNumber);
				break;
			
			default:
				break;
		}
		
	}
	
	
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void updateLocation(Location location, Product product, BigDecimal qty, boolean current, boolean future, boolean isIncrement, 
			LocalDate lastFutureStockMoveDate, TrackingNumber trackingNumber)  {
		
		this.updateLocation(location, product, qty, current, future, isIncrement, lastFutureStockMoveDate);
		
		if(trackingNumber != null)  {
			this.updateDetailLocation(location, product, qty, current, future, isIncrement, lastFutureStockMoveDate, trackingNumber);
		}
		
	}
	
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void updateLocation(Location location, Product product, BigDecimal qty, boolean current, boolean future, boolean isIncrement, LocalDate lastFutureStockMoveDate)  {
		
		LocationLine locationLine = this.getLocationLine(location, product);
		
		LOG.debug("Mise à jour du stock : Entrepot? {}, Produit? {}, Quantité? {}, Actuel? {}, Futur? {}, Incrément? {}, Date? {}, Num de suivi? {} ", 
				new Object[] { location.getName(), product.getCode(), qty, current, future, isIncrement, lastFutureStockMoveDate });
		
		locationLine = this.updateLocation(locationLine, qty, current, future, isIncrement, lastFutureStockMoveDate);
		
		locationLine.save();
		
	}
	
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void updateDetailLocation(Location location, Product product, BigDecimal qty, boolean current, boolean future, boolean isIncrement, 
			LocalDate lastFutureStockMoveDate, TrackingNumber trackingNumber)  {
		
		LocationLine detailLocationLine = this.getDetailLocationLine(location, product, trackingNumber);
		
		LOG.debug("Mise à jour du detail du stock : Entrepot? {}, Produit? {}, Quantité? {}, Actuel? {}, Futur? {}, Incrément? {}, Date? {}, Num de suivi? {} ", 
				new Object[] { location.getName(), product.getCode(), qty, current, future, isIncrement, lastFutureStockMoveDate, trackingNumber});
		
		detailLocationLine = this.updateLocation(detailLocationLine, qty, current, future, isIncrement, lastFutureStockMoveDate);
		
		detailLocationLine.save();
		
	}
	
	
	public LocationLine updateLocation(LocationLine locationLine, BigDecimal qty, boolean current, boolean future, boolean isIncrement, 
			LocalDate lastFutureStockMoveDate)  {
		
		if(current)  {
			if(isIncrement)  {
				locationLine.setCurrentQty(locationLine.getCurrentQty().add(qty));
			}
			else  {
				locationLine.setCurrentQty(locationLine.getCurrentQty().subtract(qty));
			}
			
		}
		if(future)  {
			if(isIncrement)  {
				locationLine.setFutureQty(locationLine.getFutureQty().add(qty));
			}
			else  {
				locationLine.setFutureQty(locationLine.getFutureQty().subtract(qty));
			}
			locationLine.setLastFutureStockMoveDate(lastFutureStockMoveDate);
		}
		
		return locationLine;
	}
	
	
	public LocationLine getLocationLine(Location location, Product product)  {
		
		LocationLine locationLine = this.getLocationLine(location.getLocationLineList(), product);
		
		if(locationLine == null)  {
			locationLine = this.createLocationLine(location, product);
		}
		
		LOG.debug("Récupération ligne de stock: Entrepot? {}, Produit? {}, Qté actuelle? {}, Qté future? {}, Date? {} ", 
				new Object[] { locationLine.getLocation().getName(), product.getCode(), 
				locationLine.getCurrentQty(), locationLine.getFutureQty(), locationLine.getLastFutureStockMoveDate() });
		
		return locationLine;
	}
	
	
	public LocationLine getDetailLocationLine(Location detailLocation, Product product, TrackingNumber trackingNumber)  {
		
		LocationLine detailLocationLine = this.getDetailLocationLine(detailLocation.getDetailsLocationLineList(), product, trackingNumber);
		
		if(detailLocationLine == null)  {
			detailLocationLine = this.createDetailLocationLine(detailLocation, product, trackingNumber);
		}
		
		LOG.debug("Récupération ligne de détail de stock: Entrepot? {}, Produit? {}, Qté actuelle? {}, Qté future? {}, Date? {}, Num de suivi? {} ", 
				new Object[] { detailLocationLine.getDetailsLocation().getName(), product.getCode(), 
				detailLocationLine.getCurrentQty(), detailLocationLine.getFutureQty(), detailLocationLine.getLastFutureStockMoveDate(), detailLocationLine.getTrackingNumber() });
		
		return detailLocationLine;
	}
	
	
	public LocationLine getLocationLine(List<LocationLine> locationLineList, Product product)  {
		
		for(LocationLine locationLine : locationLineList)  {
			
			if(locationLine.getProduct().equals(product))  {
				return locationLine;
			}
			
		}
		
		return null;
	}
	
	
	public LocationLine getDetailLocationLine(List<LocationLine> detailLocationLineList, Product product, TrackingNumber trackingNumber)  {
		
		for(LocationLine detailLocationLine : detailLocationLineList)  {
			
			if(detailLocationLine.getProduct().equals(product) && detailLocationLine.getTrackingNumber().equals(trackingNumber))  {
				return detailLocationLine;
			}
			
		}
		
		return null;
	}
	
	
	public LocationLine createLocationLine(Location location, Product product)  {
		
		LOG.debug("Création d'une ligne de stock : Entrepot? {}, Produit? {} ", new Object[] { location.getName(), product.getCode() });
		
		LocationLine locationLine = new LocationLine();
		
		locationLine.setLocation(location);
		locationLine.setProduct(product);
		locationLine.setCurrentQty(BigDecimal.ZERO);
		locationLine.setFutureQty(BigDecimal.ZERO);
		
		return locationLine;
		
	}
	
	
	public LocationLine createDetailLocationLine(Location location, Product product, TrackingNumber trackingNumber)  {
		
		LOG.debug("Création d'une ligne de détail de stock : Entrepot? {}, Produit? {} ", new Object[] { location.getName(), product.getCode() });
		
		LocationLine detailLocationLine = new LocationLine();
		
		detailLocationLine.setDetailsLocation(location);
		detailLocationLine.setProduct(product);
		detailLocationLine.setCurrentQty(BigDecimal.ZERO);
		detailLocationLine.setFutureQty(BigDecimal.ZERO);
		detailLocationLine.setTrackingNumber(trackingNumber);
		
		return detailLocationLine;
		
	}
	
	
}
