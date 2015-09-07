import org.grails.paypal.BuyerInformation
import org.grails.paypal.Payment
import org.grails.paypal.PaymentItem
import org.grails.paypal.USState;

import etezia.message.NotificationService
import etezia.order.Order
import etezia.order.OrderService
import etezia.shop.ShopProduct
import etezia.util.ErrorLog
import grails.plugin.springsecurity.annotation.Secured
import grails.util.Environment
import etezia.Utils

@Secured(['permitAll'])
class PaypalController extends org.grails.paypal.PaypalController{
	OrderService orderService
	transient slugGeneratorService
	
	static allowedMethods = [buy: 'POST', notifyPaypal: 'POST']

	def notifyPaypal = {
		log.debug "Received IPN notification from PayPal Server ${params}"
		def config = grailsApplication.config.grails.paypal
		def server = config.server
		def login = params.email ?: config.email
		if (!server || !login) throw new IllegalStateException("Paypal misconfigured! You need to specify the Paypal server URL and/or account email. Refer to documentation.")

		params.cmd = "_notify-validate"
		def queryString = params.toQueryString()[1..-1]

		log.debug "Sending back query $queryString to PayPal server $server"
		def url = new URL(server)
		def conn = url.openConnection()
		conn.doOutput = true
		def writer = new OutputStreamWriter(conn.getOutputStream())
		writer.write queryString
		writer.flush()

		def result = conn.inputStream.text?.trim()

		log.debug "Got response from PayPal IPN $result"

		def payment = Payment.findByTransactionId(params.transactionId)
		log.debug "Payment found:  $payment"
		if (payment && result == 'VERIFIED') {
			if (params.receiver_email != login) {
				log.warn """WARNING: receiver_email parameter received from PayPal does not match configured e-mail. This request is possibly fraudulent!
REQUEST INFO: ${params}
				"""
			}
			else {
				request.payment = payment
				def status = params.payment_status
				if (payment.status != Payment.COMPLETE && payment.status != Payment.CANCELLED) {
					if (payment.paypalTransactionId && payment.paypalTransactionId == params.txn_id) {
						log.warn """WARNING: Request tried to re-use and old PayPal transaction id. This request is possibly fraudulent!
		REQUEST INFO: ${params} """
					}
					else if (status == 'Completed') {
						payment.paypalTransactionId = params.txn_id
						payment.status = Payment.COMPLETE
						updateBuyerInformation(payment, params)
						try{
							Order order = Order.findByTransactionId(params.transactionId)
							if(order && !order.paid){
								orderService.updateOrderPaid(order)		
							}		
						}catch(Exception e){
							log.error("transactionid: " + params.transactionId )
							log.error(e.printStackTrace())
							ErrorLog errorLog = new ErrorLog();
							errorLog.setSource("/paypal/notifyPaypal/"+" ** Transaction ID: "+params.transactionId);
							errorLog.setMessage(e.toString());
							
							StringWriter errors = new StringWriter();
							e.printStackTrace(new PrintWriter(errors));
							errorLog.setDetail(errors.toString().substring(0, ErrorLog.getDETAIL_MAXSIZE()));
							errorLog.save()
						}
						log.info "Verified payment ${payment} as COMPLETE"
					} else if (status == 'Pending') {
						payment.paypalTransactionId = params.txn_id
						payment.status = Payment.PENDING
						updateBuyerInformation(payment, params)
						log.info "Verified payment ${payment} as PENDING"
					} else if (status == 'Failed') {
						payment.paypalTransactionId = params.txn_id
						payment.status = Payment.FAILED
						updateBuyerInformation(payment, params)
						log.info "Verified payment ${payment} as FAILED"
					}
				}
				payment.save(flush: true)
			}
		}
		else {
			log.debug "Error with PayPal IPN response: [$result] and Payment: [${payment?.transactionId}]"
		}
		log.debug "Rendered OK"
		render "OK" // Paypal needs a response, otherwise it will send the notification several times!
	}

	void updateBuyerInformation(payment, params) {
		BuyerInformation buyerInfo = payment.buyerInformation ?: new BuyerInformation()
		buyerInfo.populateFromPaypal(params)
		payment.buyerInformation = buyerInfo
	}

	def success = {
		def payment = Payment.findByTransactionId(params.transactionId)
		log.debug "Success notification received from PayPal for $payment with transaction id ${params.transactionId}"
		if (payment) {
			request.payment = payment
			if (payment.status != Payment.COMPLETE) {
				payment.status = Payment.COMPLETE
				payment.save(flush: true)
			}

			if (params.returnAction || params.returnController) {
				def args = [:]
				if (params.returnAction) args.action = params.returnAction
				if (params.returnController) args.controller = params.returnController
				args.params = params
				redirect(args)
			}
			else {
				return [payment: payment]
			}
		}
		else {
			response.sendError 403
		}
	}

	def cancel = {
		def payment = Payment.findByTransactionId(params.transactionId)
		log.debug "Cancel notification received from PayPal for $payment with transaction id ${params.transactionId}"
		if (payment) {
			request.payment = payment
			if (payment.status != Payment.COMPLETE) {
				payment.status = Payment.CANCELLED
				payment.save(flush: true)
				if (params.cancelAction || params.cancelController) {
					def args = [:]
					if (params.cancelAction) args.action = params.cancelAction
					if (params.cancelController) args.controller = params.cancelController
					args.params = params
					redirect(args)
				}
				else {
					return [payment: payment]
				}
			}
			else {
				response.sendError 403
			}
		}
		else {
			response.sendError 403
		}

	}

	def buy = {
		def payment
		if (params.transactionId) {
			payment = Payment.findByTransactionId(params.transactionId)
		}
		else {
			payment = new Payment(params)
			payment.addToPaymentItems(new PaymentItem(params))
		}

		if (payment?.id) log.debug "Resuming existing transaction $payment"
		if (payment?.validate()) {
			request.payment = payment
			payment.save(flush: true)
			def config = grailsApplication.config.grails.paypal
			def server = config.server
			def baseUrl = params.baseUrl
			def login = params.email ?: config.email
			if (!server || !login) throw new IllegalStateException("Paypal misconfigured! You need to specify the Paypal server URL and/or account email. Refer to documentation.")

			def commonParams = [buyerId: payment.buyerId, transactionId: payment.transactionId]
			if (params.returnAction) {
				commonParams.returnAction = params.returnAction
			}
			if (params.returnController) {
				commonParams.returnController = params.returnController
			}
			if (params.cancelAction) {
				commonParams.cancelAction = params.cancelAction
			}
			if (params.cancelController) {
				commonParams.cancelController = params.cancelController
			}
			def notifyURL = g.createLink(absolute: baseUrl==null, base: baseUrl, controller: 'paypal', action: 'notifyPaypal', params: commonParams).encodeAsURL()
			def successURL = g.createLink(absolute: baseUrl==null, base: baseUrl, controller: 'paypal', action: 'success', params: commonParams).encodeAsURL()
			def cancelURL = g.createLink(absolute: baseUrl==null, base: baseUrl, controller: 'paypal', action: 'cancel', params: commonParams).encodeAsURL()

			def url = new StringBuffer("$server?")
			url << "cmd=_xclick&"
			url << "business=$login&"
			url << "item_name=${payment.paymentItems[0].itemName}&"
			url << "item_number=${payment.paymentItems[0].itemNumber}&"
			url << "quantity=${payment.paymentItems[0].quantity}&"
			url << "amount=${payment.paymentItems[0].amount}&"
			if (payment.paymentItems[0].discountAmount > 0) {
				url << "discount_amount=${payment.paymentItems[0].discountAmount}&"
			}
			url << "tax=${payment.tax}&"
			url << "currency_code=${payment.currency}&"
			if (params.lc)
				url << "lc=${params.lc}&"
			url << "notify_url=${notifyURL}&"
			url << "return=${successURL}&"
			url << "cancel_return=${cancelURL}"

			log.debug "Redirection to PayPal with URL: $url"

			redirect(url: url)
		}
		else {
			flash.payment = payment
			redirect(url: params.originalURL)
		}
	}
	@Secured(['ROLE_USER'])
	def uploadCart () {
		//Assumes the Payment has been pre-populated and saved by whatever cart mechanism
		//you are using...
		def payment = Payment.findByTransactionId(params.transactionId)
		log.debug "Uploading cart: $payment"
		def config = grailsApplication.config.grails.paypal
		def server = config.server
		def login = params.email ?: config.email
		if (!server || !login) throw new IllegalStateException("Paypal misconfigured! You need to specify the Paypal server URL and/or account email. Refer to documentation.")
		def commonParams = [buyerId: payment.buyerId, transactionId: payment.transactionId]
		if (params.returnAction) {
			commonParams.returnAction = params.returnAction
		}
		if (params.returnController) {
			commonParams.returnController = params.returnController
		}
		if (params.cancelAction) {
			commonParams.cancelAction = params.cancelAction
		}
		if (params.cancelController) {
			commonParams.cancelController = params.cancelController
		}
		
		def tempNotifyUrl = g.createLink(absolute: true, controller: 'paypal', action: 'notifyPaypal', params: commonParams)
		
		Environment.executeForCurrentEnvironment {
			development {
			  tempNotifyUrl = tempNotifyUrl.replaceAll("localhost:8080", "26d21ce5.ngrok.com")//ngrok linkiyle degisecek
			}
		}
		
		def notifyURL = tempNotifyUrl.encodeAsURL()
		def successURL = g.createLink(absolute: true, controller: 'paypal', action: 'success', params: commonParams).encodeAsURL()
		def cancelURL = g.createLink(absolute: true, controller: 'paypal', action: 'cancel', params: commonParams).encodeAsURL()

		def url = new StringBuffer("$server?")
		url << "cmd=_cart&upload=1&"
		url << "business=$login&"
		if (params.pageStyle) {
			url << "page_style=${params.pageStyle}&"
		}
		
		url << "lc=tr_TR&"
	    //url << "charset=utf-8&"
		url << "cpp_headerback_color=F26722&"
		url << "cpp_payflow_color=F26722&"
		url << "cpp_cart_border_color=F26722&"
		url << "no_note=1&"
		url << "cbt=Return to domain.com and view your order&"
		url << "image_url=https://s3-eu-west-1.amazonaws.com/sample/logo_paypal.png&"	
		url << "cpp_header_image=https://s3-eu-west-1.amazonaws.com/sample/logo_paypal_156.png&"
		
		Order order = Order.findByTransactionId(params.transactionId)
		
		//if (params.addressOverride) {
			url << "address_override=1&"
			url << "first_name=${order.user.firstname}&"
			url << "last_name=${order.user.lastname}&"
			if(order.address?.detail ){
				def adrs = order.address.detail
				try{
				if(adrs.length() >99)
					adrs = order.address.detail[0..97]
				url << "address1=${adrs}&"
				}catch(Exception e){
					log.error(e.printStackTrace())
				}
			}
		/*	if (address.addressLineTwo) {
				url << "address2=${address.addressLineTwo}&"
			}
		*/
			url << "city=ANKARA&"
			url << "country=TURKEY&"
			url << "email=${order.user.email}&"
			url << "night_phone_a=&"
			if(order.address?.phone)
				url << "night_phone_b=${order.address.phone}&"
			else if(order.user?.phone)
				url << "night_phone_b=${order.user.phone}&"
			//url << "night_phone_c=${address.phoneSuffix}&"
			//url << "state=${address.state}&"
			//url << "zip=${address.zipCode}&"
		//}
	    if (params.noShipping) {		
			url << "no_shipping=1&"
		//	url << "address_override=1&"
		//	url << "first_name=${order.user.firstname}&"
		//	url << "last_name=${order.user.lastname}&"
		}
		payment.paymentItems.eachWithIndex {paymentItem, i ->
			def itemId = i + 1
			String  itemName = ""+paymentItem.itemName	
			itemName = slugGeneratorService.generateSlug(ShopProduct.class, "name", itemName.replaceAll('ı', 'i'))
			itemName = itemName.replaceAll('-', ' ')
			itemName = Utils.capitalizeFirstLetterOfEachWord(itemName)
			url << "item_name_${itemId}=${itemName}&"//.encodeAsURL()
			url << "item_number_${itemId}=${paymentItem.itemNumber}&"
			url << "quantity_${itemId}=${paymentItem.quantity}&"
			url << "amount_${itemId}=${paymentItem.amount}&"
			if (payment.discountCartAmount == 0 && paymentItem.discountAmount > 0) {
				url << "discount_amount_${itemId}=${paymentItem.discountAmount}&"
			}
		}
		if (payment.discountCartAmount > 0) {
			url << "discount_cart_amount_${payment.discountCartAmount}&"
		}
		url << "currency_code=${payment.currency}&"
		url << "notify_url=${notifyURL}&"
		url << "return=${successURL}&"
		url << "cancel_return=${cancelURL}&"
		url << "rm=2"
		
		log.debug "Redirection to PayPal with URL: $url"

		redirect(url: url)
	}

}

// This is a first version that only applies to the U.S. - Can anybody write an i18n-enabled version
// that Paypal can still understand?

class ShippingAddressCommand {
	String firstName
	String lastName
	String addressLineOne
	String addressLineTwo
	String city
	USState state
	String country = 'US'
	String zipCode
	String areaCode
	String phonePrefix
	String phoneSuffix

	static constraints = {
		firstName(blank: false)
		lastName(blank: false)
		addressLineOne(blank: false)
		addressLineTwo(nullable: true, blank: true)
		city(blank: false)
		country(blank: false)
		zipCode(blank: false, matches: /\d{5}/)
		areaCode(blank: false, matches: /\d{3}/)
		phonePrefix(blank: false, matches: /\d{3}/)
		phoneSuffix(blank: false, matches: /\d{4}/)
	}

}


